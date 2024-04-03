/*
 * Copyright (c) 2023 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator.microsoft.teamwork;

import com.avispl.symphony.api.dal.dto.monitor.aggregator.AggregatedDevice;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

public class MicrosoftTeamworkAggregatorCommunicatorTest {

    MicrosoftTeamworkAggregatorCommunicator microsoftTeamworkAggregatorCommunicator;

    @BeforeEach
    public void setUp() throws Exception {
        microsoftTeamworkAggregatorCommunicator = new MicrosoftTeamworkAggregatorCommunicator();
        // AVISPL:
        microsoftTeamworkAggregatorCommunicator.setTenantId("");
        microsoftTeamworkAggregatorCommunicator.setLogin("");
        microsoftTeamworkAggregatorCommunicator.setPassword("");

        // SYNCAGENT:
//        microsoftTeamworkAggregatorCommunicator.setTenantId("");
//        microsoftTeamworkAggregatorCommunicator.setLogin("");
//        microsoftTeamworkAggregatorCommunicator.setPassword("");

        microsoftTeamworkAggregatorCommunicator.setHost("graph.microsoftonline.com");
        microsoftTeamworkAggregatorCommunicator.init();
    }

    @Test
    public void testCollectDeviceProperties() throws Exception {
        List<AggregatedDevice> devicesList = microsoftTeamworkAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(30000);
        devicesList = microsoftTeamworkAggregatorCommunicator.retrieveMultipleStatistics();
        Assertions.assertNotNull(devicesList);
        Assertions.assertFalse(devicesList.isEmpty());
    }

    @Test
    public void testDevicesCollectionWithDeviceIdFilter() throws Exception {
        microsoftTeamworkAggregatorCommunicator.setDeviceUniqueIdFilter("233fbc5b7ab8489ed1a3a44f49da0875ff457fe31d7655844dd8615c421f4d05,8756c6335ca08304a1ba8de3ddec227c30ab9c50b21f8d55888cfe4c463f69b0, a580fb16d0c44dbeaa8da60af49fa51c6f4b2e1ecffeb78dabd1200256acf296");
        microsoftTeamworkAggregatorCommunicator.setDeviceTypeFilter("CollaborationBar,TeamsRoom");
        microsoftTeamworkAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(30000);
        List<AggregatedDevice> devicesList = microsoftTeamworkAggregatorCommunicator.retrieveMultipleStatistics();
        Assertions.assertNotNull(devicesList);
        Assertions.assertFalse(devicesList.isEmpty());
    }

    @Test
    public void testDevicesCollectionWithLimitsAndFilters_1() throws Exception {
        microsoftTeamworkAggregatorCommunicator.setDeviceUniqueIdFilter("233fbc5b7ab8489ed1a3a44f49da0875ff457fe31d7655844dd8615c421f4d05,8756c6335ca08304a1ba8de3ddec227c30ab9c50b21f8d55888cfe4c463f69b0, a580fb16d0c44dbeaa8da60af49fa51c6f4b2e1ecffeb78dabd1200256acf296");
        microsoftTeamworkAggregatorCommunicator.setDeviceTypeFilter("CollaborationBar,TeamsRoom");
        microsoftTeamworkAggregatorCommunicator.setMaxDeviceLimit(1);
        microsoftTeamworkAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(30000);
        List<AggregatedDevice> devicesList = microsoftTeamworkAggregatorCommunicator.retrieveMultipleStatistics();
        Assertions.assertEquals(1, devicesList.size());
    }

    @Test
    public void testDevicesCollectionWithLimitsAndFilters_3() throws Exception {
        microsoftTeamworkAggregatorCommunicator.setDeviceUniqueIdFilter("233fbc5b7ab8489ed1a3a44f49da0875ff457fe31d7655844dd8615c421f4d05,8756c6335ca08304a1ba8de3ddec227c30ab9c50b21f8d55888cfe4c463f69b0, a580fb16d0c44dbeaa8da60af49fa51c6f4b2e1ecffeb78dabd1200256acf296");
        microsoftTeamworkAggregatorCommunicator.setDeviceTypeFilter("CollaborationBar,TeamsRoom");
        microsoftTeamworkAggregatorCommunicator.setMaxDeviceLimit(3);
        microsoftTeamworkAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(30000);
        List<AggregatedDevice> devicesList = microsoftTeamworkAggregatorCommunicator.retrieveMultipleStatistics();
        Assertions.assertEquals(3, devicesList.size());
    }

    @Test
    public void testDevicesCollectionWithLimitsAndNoFilters_5() throws Exception {
        microsoftTeamworkAggregatorCommunicator.setDeviceUniqueIdFilter("");
        microsoftTeamworkAggregatorCommunicator.setDeviceTypeFilter("");
        microsoftTeamworkAggregatorCommunicator.setMaxDeviceLimit(5);
        microsoftTeamworkAggregatorCommunicator.retrieveMultipleStatistics();
        Thread.sleep(30000);
        List<AggregatedDevice> devicesList = microsoftTeamworkAggregatorCommunicator.retrieveMultipleStatistics();
        Assertions.assertEquals(5, devicesList.size());
    }
}
