/*
 * Copyright (c) 2023 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator.microsoft.teamwork;

import com.avispl.symphony.api.dal.control.Controller;
import com.avispl.symphony.api.dal.dto.control.ControllableProperty;
import com.avispl.symphony.api.dal.dto.monitor.ExtendedStatistics;
import com.avispl.symphony.api.dal.dto.monitor.Statistics;
import com.avispl.symphony.api.dal.dto.monitor.aggregator.AggregatedDevice;
import com.avispl.symphony.api.dal.error.CommandFailureException;
import com.avispl.symphony.api.dal.monitor.Monitorable;
import com.avispl.symphony.api.dal.monitor.aggregator.Aggregator;
import com.avispl.symphony.dal.aggregator.parser.AggregatedDeviceProcessor;
import com.avispl.symphony.dal.aggregator.parser.PropertiesMapping;
import com.avispl.symphony.dal.aggregator.parser.PropertiesMappingParser;
import com.avispl.symphony.dal.communicator.RestCommunicator;
import com.avispl.symphony.dal.util.StringUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.util.CollectionUtils;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.DefaultUriBuilderFactory;

import javax.security.auth.login.FailedLoginException;
import javax.security.auth.login.LoginException;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

import static com.avispl.symphony.dal.communicator.microsoft.teamwork.Constant.Path.*;
import static com.avispl.symphony.dal.communicator.microsoft.teamwork.Constant.URL.*;
import static com.avispl.symphony.dal.communicator.microsoft.teamwork.Constant.Util.FILTER_PARAMETER;
import static java.util.stream.Collectors.toList;

/**
 * Communicator retrieves information about Teamwork Devices, available on a specific account, used for data access.
 * Provides all the necessary monitoring information, such as health statuses, peripherals details, software and firmware versions, etc..
 *
 * @author Maksym.Rossiytsev
 * @since 1.0.0
 */
public class MicrosoftTeamworkAggregatorCommunicator extends RestCommunicator implements Aggregator, Monitorable, Controller {

    /**
     * Process that is running constantly and triggers collecting data from Graph API endpoints, based on the given timeouts and thresholds.
     *
     * @author Maksym.Rossiytsev
     * @since 1.0.0
     */
    class TeamworkDeviceDataLoader implements Runnable {
        private volatile boolean inProgress;

        public TeamworkDeviceDataLoader() {
            inProgress = true;
        }

        @Override
        public void run() {
            mainloop:
            while (inProgress) {
                try {
                    TimeUnit.MILLISECONDS.sleep(500);
                } catch (InterruptedException e) {
                    // Ignore for now
                }

                if (!inProgress) {
                    break mainloop;
                }

                // next line will determine whether MS Graph API monitoring was paused
                updateAggregatorStatus();
                if (devicePaused) {
                    continue mainloop;
                }

                try {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Fetching devices list");
                    }
                    fetchDevicesList();
                    if (logger.isDebugEnabled()) {
                        logger.debug("Fetched devices list: " + aggregatedDevices);
                    }
                    if (accessToken != null) {
                        // If we are past the point of fetchDevicesList() call but do not still have accessToken -
                        // we are skipping http calls attempts, and authorization is still broken
                        hasAuthError = false;
                    }
                    hasApiError = false;
                } catch (LoginException le) {
                    logger.error("Unable to authorize using tenantId, clientId and clientSecret provided.");
                    hasAuthError = true;
                } catch (Exception e) {
                    logger.error("Error occurred during device list retrieval: " + e.getMessage() + " with cause: " + e.getCause().getMessage(), e);
                    hasApiError = true;
                }

                if (!inProgress) {
                    break mainloop;
                }

                int aggregatedDevicesCount = aggregatedDevices.size();
                if (aggregatedDevicesCount == 0) {
                    continue mainloop;
                }

                while (nextDevicesCollectionIterationTimestamp > System.currentTimeMillis()) {
                    try {
                        TimeUnit.MILLISECONDS.sleep(1000);
                    } catch (InterruptedException e) {
                        //
                    }
                }

                for (AggregatedDevice aggregatedDevice : aggregatedDevices.values()) {
                    if (!inProgress) {
                        break;
                    }
                    devicesExecutionPool.add(executorService.submit(() -> {
                        String deviceId = aggregatedDevice.getDeviceId();
                        try {
                            populateDeviceDetails(deviceId);
                        } catch (Exception e) {
                            logger.error(String.format("Exception during Teamwork Device '%s' data processing.", aggregatedDevice.getDeviceName()), e);
                        }
                    }));
                }

                do {
                    try {
                        TimeUnit.MILLISECONDS.sleep(500);
                    } catch (InterruptedException e) {
                        if (!inProgress) {
                            break;
                        }
                    }
                    devicesExecutionPool.removeIf(Future::isDone);
                } while (!devicesExecutionPool.isEmpty());

                // We don't want to fetch devices statuses too often, so by default it's currentTime + 30s
                // otherwise - the variable is reset by the retrieveMultipleStatistics() call, which
                // launches devices detailed statistics collection
                nextDevicesCollectionIterationTimestamp = System.currentTimeMillis() + 30000;

                if (logger.isDebugEnabled()) {
                    logger.debug("Finished collecting devices statistics cycle at " + new Date());
                }
            }
            // Finished collecting
        }

        /**
         * Triggers main loop to stop
         */
        public void stop() {
            inProgress = false;
        }
    }

    boolean hasAuthError = false;
    boolean hasApiError = false;

    /**
     * Client ID used for oauth access token generation. (login)
     * */
    String clientId;

    /**
     * Client Secret used for oauth access token generation. (password)
     * */
    String clientSecret;

    /**
     * Tenant ID used for oauth access token generation.
     * */
    String tenantId;

    /**
     * Access token. Regenerated if does not exist, or if is outdated.
     * */
    String accessToken;

    /**
     * This parameter holds timestamp of when we need to stop performing API calls
     * It used when device stop retrieving statistic. Updated each time of called #retrieveMultipleStatistics
     */
    private volatile long validRetrieveStatisticsTimestamp;

    /**
     * If the {@link MicrosoftTeamworkAggregatorCommunicator#deviceMetaDataRetrievalTimeout} is set to a value that is too small -
     * devices list will be fetched too frequently. In order to avoid this - the minimal value is based on this value.
     */
    private static final long defaultMetaDataTimeout = 60 * 1000 / 2;

    /**
     * Device metadata retrieval timeout. The general devices list is retrieved once during this time period.
     */
    private long deviceMetaDataRetrievalTimeout = 60 * 1000 / 2;

    /**
     * Aggregator inactivity timeout. If the {@link MicrosoftTeamworkAggregatorCommunicator#retrieveMultipleStatistics()}  method is not
     * called during this period of time - device is considered to be paused, thus the Cloud API
     * is not supposed to be called
     */
    private static final long retrieveStatisticsTimeOut = 3 * 60 * 1000;

    /**
     * Device adapter instantiation timestamp.
     */
    private long adapterInitializationTimestamp;

    /**
     * Time period within which the device metadata (basic devices information) cannot be refreshed.
     * Ignored if device list is not yet retrieved or the cached device list is empty {@link MicrosoftTeamworkAggregatorCommunicator#aggregatedDevices}
     */
    private volatile long validDeviceMetaDataRetrievalPeriodTimestamp;

    /**
     * We don't want the statistics to be collected constantly, because if there's not a big list of devices -
     * new devices statistics loop will be launched before the next monitoring iteration. To avoid that -
     * this variable stores a timestamp which validates it, so when the devices statistics is done collecting, variable
     * is set to currentTime + 30s, at the same time, calling {@link #retrieveMultipleStatistics()} and updating the
     * {@link #aggregatedDevices} resets it to the currentTime timestamp, which will re-activate data collection.
     */
    private static long nextDevicesCollectionIterationTimestamp;

    /**
     * Indicates whether a device is considered as paused.
     * True by default so if the system is rebooted and the actual value is lost -> the device won't start stats
     * collection unless the {@link MicrosoftTeamworkAggregatorCommunicator#retrieveMultipleStatistics()} method is called which will change it
     * to a correct value
     */
    private volatile boolean devicePaused = true;

    /**
     * Grant filtering by unique device ids, e.g 233fbc5b7ab8489ed1a3a44f49da0875ff457fe31d7655844dd8615c421f4d05
     * */
    private List<String> deviceUniqueIdFilter;

    /**
     * Grant filtering by device type, e.g CollaborationBar, TeamsRoom etc.
     * */
    private List<String> deviceTypeFilter;

    /**
     * Grant limiting the number of aggregated devices
     * */
    private int maxDeviceLimit = 100;

    /**
     * Devices this aggregator is responsible for
     * Data is cached and retrieved every {@link #defaultMetaDataTimeout}
     */
    private ConcurrentHashMap<String, AggregatedDevice> aggregatedDevices = new ConcurrentHashMap<>();

    /**
     * Pool for keeping all the async operations in, to track any operations in progress and cancel them if needed
     */
    private List<Future> devicesExecutionPool = new ArrayList<>();

    /**
     * Executor that runs all the async operations, that {@link #deviceDataLoader} is posting and
     * {@link #devicesExecutionPool} is keeping track of
     */
    private static ExecutorService executorService;

    /**
     * Runner service responsible for collecting data and posting processes to {@link #devicesExecutionPool}
     */
    private TeamworkDeviceDataLoader deviceDataLoader;

    private AggregatedDeviceProcessor aggregatedDeviceProcessor;
    private Properties adapterProperties;

    /**
     * Retrieves {@link #deviceTypeFilter}
     *
     * @return value of {@link #deviceTypeFilter}
     */
    public List<String> getDeviceTypeFilter() {
        return deviceTypeFilter;
    }

    /**
     * Sets {@link #deviceTypeFilter} value
     *
     * @param deviceTypeFilter new value of {@link #deviceTypeFilter}
     */
    public void setDeviceTypeFilter(String deviceTypeFilter) {
        if (StringUtils.isNullOrEmpty(deviceTypeFilter)) {
            return;
        }
        this.deviceTypeFilter = Arrays.stream(deviceTypeFilter.split(",")).map(String::trim).collect(toList());
    }

    /**
     * Retrieves {@link #maxDeviceLimit}
     *
     * @return value of {@link #maxDeviceLimit}
     */
    public int getMaxDeviceLimit() {
        return maxDeviceLimit;
    }

    /**
     * Sets {@link #maxDeviceLimit} value
     *
     * @param deviceLimit new value of {@link #maxDeviceLimit}
     */
    public void setMaxDeviceLimit(int deviceLimit) {
        this.maxDeviceLimit = deviceLimit;
    }

    /**
     * Retrieves {@link #deviceUniqueIdFilter}
     *
     * @return value of {@link #deviceUniqueIdFilter}
     */
    public List<String> getDeviceUniqueIdFilter() {
        return deviceUniqueIdFilter;
    }

    /**
     * Sets {@link #deviceUniqueIdFilter} value
     *
     * @param deviceUniqueIdFilter new value of {@link #deviceUniqueIdFilter}
     */
    public void setDeviceUniqueIdFilter(String deviceUniqueIdFilter) {
        if (StringUtils.isNullOrEmpty(deviceUniqueIdFilter)) {
            return;
        }
        this.deviceUniqueIdFilter = Arrays.stream(deviceUniqueIdFilter.split(",")).map(String::trim).collect(toList());
    }

    /**
     * Retrieves {@link #tenantId}
     *
     * @return value of {@link #tenantId}
     */
    public String getTenantId() {
        return tenantId;
    }

    /**
     * Sets {@link #tenantId} value
     *
     * @param tenantId new value of {@link #tenantId}
     */
    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    /**
     * Retrieves {@link #deviceMetaDataRetrievalTimeout}
     *
     * @return value of {@link #deviceMetaDataRetrievalTimeout}
     */
    public long getDeviceMetaDataRetrievalTimeout() {
        return deviceMetaDataRetrievalTimeout;
    }

    /**
     * Sets {@link #deviceMetaDataRetrievalTimeout} value
     *
     * @param deviceMetaDataRetrievalTimeout new value of {@link #deviceMetaDataRetrievalTimeout}
     */
    public void setDeviceMetaDataRetrievalTimeout(long deviceMetaDataRetrievalTimeout) {
        this.deviceMetaDataRetrievalTimeout = Math.max(defaultMetaDataTimeout, deviceMetaDataRetrievalTimeout);
    }

    public MicrosoftTeamworkAggregatorCommunicator() throws IOException {
        Map<String, PropertiesMapping> mapping = new PropertiesMappingParser().loadYML("mapping/model-mapping.yml", getClass());
        aggregatedDeviceProcessor = new AggregatedDeviceProcessor(mapping);
        adapterProperties = new Properties();
        adapterProperties.load(getClass().getResourceAsStream("/version.properties"));
    }

    @Override
    protected void authenticate() throws Exception {
        MultiValueMap<String, String> request = new LinkedMultiValueMap<>();
        request.add("client_id", getLogin());
        request.add("scope", SCOPE_URL);
        request.add("grant_type", "client_credentials");
        request.add("client_secret", getPassword());

        try {
            JsonNode response = doPost(String.format(RETRIEVE_TOKEN_URL, tenantId), request, JsonNode.class);
            accessToken = response.at(ACCESS_TOKEN_PATH).asText();
        } catch (CommandFailureException cfe) {
            accessToken = null;
            throw new LoginException("Unable to authorize using tenantId, clientId and clientSecret provided.");
        }
    }

    @Override
    public String doPost(String uri, String data) throws Exception {
        try {
            return super.doPost(uri, data);
        } catch (FailedLoginException fe) {
            authenticate();
            return super.doPost(uri, data);
        }
    }

    @Override
    protected <Response> Response doGet(String uri, Class<Response> responseClass) throws Exception {
        try {
            return super.doGet(uri, responseClass);
        } catch (FailedLoginException fe) {
            authenticate();
            return super.doGet(uri, responseClass);
        }
    }

    @Override
    protected void internalInit() throws Exception {
        if (logger.isDebugEnabled()) {
            logger.debug("Internal init is called.");
        }
        adapterInitializationTimestamp = System.currentTimeMillis();

        clientId = getLogin();
        clientSecret = getPassword();

        executorService = Executors.newFixedThreadPool(8);
        executorService.submit(deviceDataLoader = new TeamworkDeviceDataLoader());

        validDeviceMetaDataRetrievalPeriodTimestamp = System.currentTimeMillis();
        super.internalInit();
    }

    @Override
    protected void internalDestroy() {
        if (logger.isDebugEnabled()) {
            logger.debug("Internal destroy is called.");
        }
        if (deviceDataLoader != null) {
            deviceDataLoader.stop();
            deviceDataLoader = null;
        }

        if (executorService != null) {
            executorService.shutdownNow();
            executorService = null;
        }

        devicesExecutionPool.forEach(future -> future.cancel(true));
        devicesExecutionPool.clear();

        aggregatedDevices.clear();
        super.internalDestroy();
    }

    @Override
    public void controlProperty(ControllableProperty controllableProperty) throws Exception {
        String deviceId = controllableProperty.getDeviceId();
        String property = controllableProperty.getProperty();

        if (deviceId == null || StringUtils.isNullOrEmpty(deviceId)) {
            return;
        }
        switch (property) {
            case "Restart":
                restartDevice(deviceId);
                break;
            case "RunDiagnostics":
                runDeviceDiagnostics(deviceId);
                break;
            default:
                if (logger.isWarnEnabled()) {
                    logger.warn(String.format("Operation %s is not supported", property));
                }
                break;
        }
    }

    @Override
    public void controlProperties(List<ControllableProperty> controllablePropertyList) throws Exception {
        if (CollectionUtils.isEmpty(controllablePropertyList)) {
            throw new IllegalArgumentException("Controllable properties cannot be null or empty");
        }
        for (ControllableProperty controllableProperty : controllablePropertyList) {
            controlProperty(controllableProperty);
        }
    }

    @Override
    public List<Statistics> getMultipleStatistics() throws LoginException {
        if (hasAuthError) {
            throw new LoginException("Unable to authorize using tenantId, clientId and clientSecret provided.");
        }
        Map<String, String> statistics = new HashMap<>();
        ExtendedStatistics extendedStatistics = new ExtendedStatistics();

        statistics.put("AdapterVersion", adapterProperties.getProperty("aggregator.version"));
        statistics.put("AdapterBuildDate", adapterProperties.getProperty("aggregator.build.date"));
        statistics.put("AdapterUptime", normalizeUptime((System.currentTimeMillis() - adapterInitializationTimestamp) / 1000));

        extendedStatistics.setStatistics(statistics);
        return Collections.singletonList(extendedStatistics);
    }

    @Override
    public List<AggregatedDevice> retrieveMultipleStatistics() throws LoginException {
        if (hasAuthError) {
            throw new LoginException("Unable to authorize using tenantId, clientId and clientSecret provided.");
        }

        long currentTimestamp = System.currentTimeMillis();
        nextDevicesCollectionIterationTimestamp = currentTimestamp;
        updateValidRetrieveStatisticsTimestamp();

        this.aggregatedDevices.values().forEach(aggregatedDevice -> aggregatedDevice.setTimestamp(currentTimestamp));
        return new ArrayList<>(this.aggregatedDevices.values());
    }

    @Override
    protected RestTemplate obtainRestTemplate() throws Exception {
        RestTemplate restTemplate = super.obtainRestTemplate();
        DefaultUriBuilderFactory defaultUriBuilderFactory = new DefaultUriBuilderFactory();
        defaultUriBuilderFactory.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.NONE);
        restTemplate.setUriTemplateHandler(defaultUriBuilderFactory);
        return restTemplate;
    }

    @Override
    protected HttpHeaders putExtraRequestHeaders(HttpMethod httpMethod, String uri, HttpHeaders headers) throws Exception {
        if (uri.contains("login")) {
            headers.remove("Content-Type");
            headers.add("Content-Type", "application/x-www-form-urlencoded");
            headers.add("Accept", "*/*");
        } else {
            headers.add("Content-Type", "application/json");
            headers.add("Authorization", "Bearer " + accessToken);
        }

        return super.putExtraRequestHeaders(httpMethod, uri, headers);
    }

    @Override
    public List<AggregatedDevice> retrieveMultipleStatistics(List<String> deviceIds) throws Exception {
        if (logger.isDebugEnabled()) {
            logger.debug("MS Teamwork Devices retrieveMultipleStatistics deviceIds=" + String.join(" ", deviceIds));
        }
        return retrieveMultipleStatistics()
                .stream()
                .filter(aggregatedDevice -> deviceIds.contains(aggregatedDevice.getDeviceId()))
                .collect(toList());
    }

    /**
     * Request urls with increased page number, until all the devices are retrieved, or target number of devices is reached
     *
     * @param uri to request data from
     * @param deviceNodes collection to store json nodes to
     * @param maxNumberOfDevices max number of devices
     *
     * @throws Exception if any error occurs
     * */
    private void fetchPaginatedResponse(String uri, List<JsonNode> deviceNodes, int maxNumberOfDevices) throws Exception {
        JsonNode response = doGet(uri, JsonNode.class);
        String nextLink = response.at(NEXT_LINK_PATH).asText();
        for (JsonNode value : response.at(VALUE_PATH)) {
            if (deviceNodes.size() >= maxNumberOfDevices) {
                break;
            }
            deviceNodes.add(value);
        }
        if (StringUtils.isNotNullOrEmpty(nextLink) && deviceNodes.size() < maxNumberOfDevices) {
            fetchPaginatedResponse(nextLink, deviceNodes, maxNumberOfDevices);
        }
    }

    /**
     * Retrieve Teamwork devices with basic information and save it to {@link #aggregatedDevices} collection
     * Filter Teamwork devices based on model name, and limit the total number of devices.
     *
     * @throws Exception if a communication error occurs
     */
    private void fetchDevicesList() throws Exception {
        long currentTimestamp = System.currentTimeMillis();
        if (validDeviceMetaDataRetrievalPeriodTimestamp > currentTimestamp) {
            if (logger.isDebugEnabled()) {
                logger.debug(String.format("General devices metadata retrieval is in cooldown. %s seconds left",
                        (validDeviceMetaDataRetrievalPeriodTimestamp - currentTimestamp) / 1000));
            }
            return;
        }
        validDeviceMetaDataRetrievalPeriodTimestamp = currentTimestamp + deviceMetaDataRetrievalTimeout;

        List<JsonNode> deviceNodes = new ArrayList<>();

        boolean blankUniqueIdFilter = (deviceUniqueIdFilter == null || deviceUniqueIdFilter.isEmpty());
        boolean blankDeviceTypeFilter = (deviceTypeFilter == null || deviceTypeFilter.isEmpty());
        if (blankUniqueIdFilter && blankDeviceTypeFilter) {
            if (logger.isDebugEnabled()) {
                logger.debug("Retrieving devices list with max limit " + maxDeviceLimit);
            }
            fetchPaginatedResponse(RETRIEVE_DEVICES_LIST_URL, deviceNodes, maxDeviceLimit);
        } else {
            if (!blankUniqueIdFilter) {
                for (String uniqueId : deviceUniqueIdFilter) {
                    try {
                        if (logger.isDebugEnabled()) {
                            logger.debug("Retrieving devices list with uniqueId filter " + uniqueId);
                        }
                        JsonNode uniqueIdResponse = doGet(RETRIEVE_DEVICES_LIST_UNIQUE_ID_FILTER_URL + String.format(FILTER_PARAMETER, uniqueId), JsonNode.class);
                        if (uniqueIdResponse != null) {
                            deviceNodes.add(uniqueIdResponse.at(VALUE_PATH).get(0));
                            if (deviceNodes.size() >= maxDeviceLimit) {
                                break;
                            }
                        }
                    } catch (Exception e) {
                        logger.error("Unable to retrieve device by uniqueId " + uniqueId, e);
                    }
                }
            }
            if (!blankDeviceTypeFilter) {
                for(String deviceType: deviceTypeFilter) {
                    if (deviceNodes.size() >= maxDeviceLimit) {
                        break;
                    }
                    if (logger.isDebugEnabled()) {
                        logger.debug("Retrieving devices list with deviceType filter " + deviceType);
                    }
                    fetchPaginatedResponse( RETRIEVE_DEVICES_LIST_DEVICE_TYPE_FILTER_URL + String.format(FILTER_PARAMETER, deviceType), deviceNodes, maxDeviceLimit);
                }
            }
        }

        for (JsonNode jsonNode : deviceNodes) {
            AggregatedDevice aggregatedDevice = new AggregatedDevice();
            if (logger.isDebugEnabled()) {
                logger.debug("Applying properties for Generic model type and source " + jsonNode);
            }
            aggregatedDeviceProcessor.applyProperties(aggregatedDevice, jsonNode, "Generic");

            JsonNode macAddresses = jsonNode.at(MAC_ADDRESSES_PATH);
            if (macAddresses != null && !macAddresses.isMissingNode()) {
                List<String> macAddressList = new ArrayList<>();
                macAddresses.forEach(macAddress -> {
                    String mac = macAddress.asText();
                    macAddressList.add(mac.substring(mac.indexOf(":") + 1));
                });
                aggregatedDevice.setMacAddresses(macAddressList);
            }
            String deviceId = aggregatedDevice.getDeviceId();
            if (!this.aggregatedDevices.containsKey(deviceId)) {
                this.aggregatedDevices.put(deviceId, aggregatedDevice);
            } else {
                updateExistingAggregatedDevice(aggregatedDevice);
            }
        }
    }

    /**
     * Update existing aggregated device metadata, to avoid having all device details rewritten upon update
     * @param newDevice to take device metadata from
     * */
    private void updateExistingAggregatedDevice(AggregatedDevice newDevice) {
        String newDeviceId = newDevice.getDeviceId();
        AggregatedDevice existingDevice = this.aggregatedDevices.get(newDeviceId);
        if (existingDevice == null) {
            return;
        }
        if (logger.isDebugEnabled()) {
            logger.debug("Updating existing aggregated device with new metadata: " + newDeviceId);
        }
        existingDevice.setType(newDevice.getType());
        existingDevice.setDeviceMake(newDevice.getDeviceMake());
        existingDevice.setSerialNumber(newDevice.getSerialNumber());
        existingDevice.setDeviceName(newDevice.getDeviceName());
        existingDevice.setDeviceModel(newDevice.getDeviceModel());
        existingDevice.setDeviceOnline(newDevice.getDeviceOnline());

        Map<String, String> newProperties = newDevice.getProperties();
        Map<String, String> oldProperties = existingDevice.getProperties();
        if (oldProperties == null) {
            oldProperties = new HashMap<>();
        }
        oldProperties.putAll(newProperties);
        existingDevice.setTimestamp(System.currentTimeMillis());
    }

    /**
     * Issue "runDiagnostics" command
     * @param deviceId for which diagnostics command should be issued
     */
    private void runDeviceDiagnostics(String deviceId) throws Exception {
        if (logger.isDebugEnabled()) {
            logger.debug("Requesting Run Diagnostics command for device: " + deviceId);
        }
        doPost(String.format(RUN_DIAGNOSTICS_URL, deviceId), null);
    }

    /**
     * Issue "restart" command
     * @param deviceId for which restart command should be issued
     */
    private void restartDevice(String deviceId) throws Exception {
        if (logger.isDebugEnabled()) {
            logger.debug("Requesting Restart command for device: " + deviceId);
        }
        doPost(String.format(RESTART_URL, deviceId), null);
    }

    /**
     * Populate Teamwork device with properties
     *
     * @param deviceId Id of teamwork device
     */
    private void populateDeviceDetails(String deviceId) {
        AggregatedDevice aggregatedDevice = aggregatedDevices.get(deviceId);
        if (aggregatedDevice == null) {
            return;
        }

        Map<String, String> properties = aggregatedDevice.getProperties();
        try {
            if (logger.isDebugEnabled()) {
                logger.debug("Requesting device activity details for device with id: " + deviceId);
            }
            JsonNode deviceActivity = doGet(String.format(RETRIEVE_DEVICES_ACTIVITY_URL, deviceId), JsonNode.class);
            aggregatedDeviceProcessor.applyProperties(properties, deviceActivity, "DeviceActivity");
        } catch (Exception ex) {
            logger.error("Unable to retrieve device activity details for device with id: " + deviceId);
        }

        try {
            if (logger.isDebugEnabled()) {
                logger.debug("Requesting device configuration details for device with id: " + deviceId);
            }
            JsonNode deviceConfig = doGet(String.format(RETRIEVE_DEVICES_CONFIGURATION_URL, deviceId), JsonNode.class);
            aggregatedDeviceProcessor.applyProperties(properties, deviceConfig, "DeviceConfiguration");
        } catch (Exception ex) {
            logger.error("Unable to retrieve device configuration details for device with id: " + deviceId);
        }

        try {
            if (logger.isDebugEnabled()) {
                logger.debug("Requesting device health details for device with id: " + deviceId);
            }
            JsonNode deviceHealth = doGet(String.format(RETRIEVE_DEVICES_HEALTH_URL, deviceId), JsonNode.class);
            aggregatedDeviceProcessor.applyProperties(properties, deviceHealth, "DeviceHealth");
        } catch (Exception ex) {
            logger.error("Unable to retrieve device health details for device with id: " + deviceId);
        }
    }

    /**
     * Update the status of the device.
     * The device is considered as paused if did not receive any retrieveMultipleStatistics()
     * calls during {@link MicrosoftTeamworkAggregatorCommunicator#validRetrieveStatisticsTimestamp}
     */
    private synchronized void updateAggregatorStatus() {
        devicePaused = validRetrieveStatisticsTimestamp < System.currentTimeMillis();
    }

    /**
     * Set new valid statistics retrieval timestamp by {@link #retrieveStatisticsTimeOut} in future
     * */
    private synchronized void updateValidRetrieveStatisticsTimestamp() {
        validRetrieveStatisticsTimestamp = System.currentTimeMillis() + retrieveStatisticsTimeOut;
        updateAggregatorStatus();
    }

    /**
     * Uptime is received in seconds, need to normalize it and make it human readable, like
     * 1 day(s) 5 hour(s) 12 minute(s) 55 minute(s)
     * Incoming parameter is may have a decimal point, so in order to safely process this - it's rounded first.
     * We don't need to add a segment of time if it's 0.
     *
     * @param uptimeSeconds value in seconds
     * @return string value of format 'x day(s) x hour(s) x minute(s) x minute(s)'
     */
    private String normalizeUptime(long uptimeSeconds) {
        StringBuilder normalizedUptime = new StringBuilder();

        long seconds = uptimeSeconds % 60;
        long minutes = uptimeSeconds % 3600 / 60;
        long hours = uptimeSeconds % 86400 / 3600;
        long days = uptimeSeconds / 86400;

        if (days > 0) {
            normalizedUptime.append(days).append(" day(s) ");
        }
        if (hours > 0) {
            normalizedUptime.append(hours).append(" hour(s) ");
        }
        if (minutes > 0) {
            normalizedUptime.append(minutes).append(" minute(s) ");
        }
        if (seconds > 0) {
            normalizedUptime.append(seconds).append(" second(s)");
        }
        return normalizedUptime.toString().trim();
    }
}
