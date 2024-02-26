/*
 * Copyright (c) 2023 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator.microsoft.teamwork;

/**
 * Microsoft Teamwork Aggregator constants
 * Includes URL, Path and Util sections
 *
 * @author Maksym.Rossiytsev
 * @since 1.0.0
 */
public interface Constant {
    /**
     * URLs used by aggregator to collect device details
     *
     * @author Maksym.Rossiytsev
     * @since 1.0.0
     */
    interface URL {
        String SCOPE_URL = "https://graph.microsoft.com/.default";
        String RETRIEVE_TOKEN_URL = "https://login.microsoftonline.com/%s/oauth2/v2.0/token";
        String RETRIEVE_DEVICES_LIST_URL = "https://graph.microsoft.com/beta/teamwork/devices";
        String RETRIEVE_DEVICES_LIST_UNIQUE_ID_FILTER_URL = "https://graph.microsoft.com/beta/teamwork/devices?$filter=hardwareDetail/uniqueId%20eq%20";
        String RETRIEVE_DEVICES_LIST_DEVICE_TYPE_FILTER_URL = "https://graph.microsoft.com/beta/teamwork/devices?$filter=deviceType%20eq%20";
        String RETRIEVE_DEVICES_ACTIVITY_URL = "https://graph.microsoft.com/beta/teamwork/devices/%s/activity";
        String RETRIEVE_DEVICES_CONFIGURATION_URL = "https://graph.microsoft.com/beta/teamwork/devices/%s/configuration";
        String RETRIEVE_DEVICES_HEALTH_URL = "https://graph.microsoft.com/beta/teamwork/devices/%s/health";
        String RUN_DIAGNOSTICS_URL = "https://graph.microsoft.com/beta/teamwork/devices/%s/runDiagnostics";
        String RESTART_URL = "https://graph.microsoft.com/beta/teamwork/devices/%s/restart";
    }

    /**
     * Path values, used to retrieve certain chunks of information directly
     *
     * @author Maksym.Rossiytsev
     * @since 1.0.0
     */
    interface Path {
        String NEXT_LINK_PATH = "/@odata.nextLink";
        String VALUE_PATH = "/value";
        String MAC_ADDRESSES_PATH = "/hardwareDetail/macAddresses";
        String ACCESS_TOKEN_PATH = "/access_token";
    }

    /**
     * Constants for utility purposes
     *
     * @author Maksym.Rossiytsev
     * @since 1.0.0
     */
    interface Util {
        String FILTER_PARAMETER = "'%s'";
    }

    /**
     * Property names constants
     * @author Maksym Rossiitsev
     * @since 0.1.0
     * */
    interface PropertyNames {
        String TOTAL_DEVICES = "MonitoredDevicesTotal";
        String LAST_CYCLE_DURATION = "LastMonitoringCycleDuration(s)";
    }
}
