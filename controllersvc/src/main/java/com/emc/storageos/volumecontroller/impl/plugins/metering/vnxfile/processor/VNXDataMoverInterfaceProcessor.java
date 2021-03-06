/*
 * Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.volumecontroller.impl.plugins.metering.vnxfile.processor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.methods.PostMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.nas.vnxfile.xmlapi.LogicalDeviceType;
import com.emc.nas.vnxfile.xmlapi.LogicalNetworkDevice;
import com.emc.nas.vnxfile.xmlapi.NameList;
import com.emc.nas.vnxfile.xmlapi.NetworkDeviceSpeed;
import com.emc.nas.vnxfile.xmlapi.ResponsePacket;
import com.emc.nas.vnxfile.xmlapi.Status;
import com.emc.storageos.plugins.BaseCollectionException;
import com.emc.storageos.plugins.common.domainmodel.Operation;
import com.emc.storageos.plugins.metering.vnxfile.VNXFileConstants;
import com.emc.storageos.volumecontroller.impl.plugins.metering.vnxfile.VNXFileProcessor;

/**
 * VNXDataMoverInterfaceProcessor is responsible to process the result received from XML API
 * Server during VNX Data Mover Interface stream processing.
 * 
 */
public class VNXDataMoverInterfaceProcessor extends VNXFileProcessor {
    private final Logger _logger = LoggerFactory.getLogger(VNXDataMoverInterfaceProcessor.class);

    @Override
    public void processResult(Operation operation, Object resultObj, Map<String, Object> keyMap) throws BaseCollectionException {
        // TODO Auto-generated method stub
        final PostMethod result = (PostMethod) resultObj;
        _logger.info("processing PortMetrics response" + resultObj);
        try {
            ResponsePacket responsePacket = (ResponsePacket) _unmarshaller
                    .unmarshal(result.getResponseBodyAsStream());

            if (null != responsePacket.getPacketFault()) {
                Status status = responsePacket.getPacketFault();
                processErrorStatus(status, keyMap);
            } else {

                List<Object> queryResponse = getQueryResponse(responsePacket);
                Iterator<Object> queryRespItr = queryResponse.iterator();

                LogicalNetworkDevice logicalNetworkDevice = null;
                // this interface to pysical port map.
                Map<String, List<String>> interPortMap = null;

                // this is mover to interportMap
                Map<String, Map<String, List<String>>> moverInterMap = new HashMap<String, Map<String, List<String>>>();
                Map<String, String> portSpeedMap = new HashMap<String, String>();
                while (queryRespItr.hasNext()) {
                    Object responseObj = queryRespItr.next();
                    if (responseObj instanceof LogicalNetworkDevice) {

                        logicalNetworkDevice = (LogicalNetworkDevice) responseObj;

                        // get logical device map
                        interPortMap = moverInterMap.get(logicalNetworkDevice.getMover());

                        if (interPortMap == null) {
                            interPortMap = new HashMap<String, List<String>>();
                        }

                        // process logical network device
                        processNetworkDevice(logicalNetworkDevice, interPortMap);

                        // add to map <movername, Map<interfaceIP, List<physicalport>>
                        if (!interPortMap.isEmpty()) {
                            moverInterMap.put(logicalNetworkDevice.getMover(), interPortMap);
                        }

                        // process logical network device speed
                        processPortSpeed(logicalNetworkDevice, portSpeedMap);

                    }
                }
                keyMap.put(VNXFileConstants.INTREFACE_PORT_MAP, moverInterMap);
                // STORAGE_PORT_SPEED_MAP
                keyMap.put(VNXFileConstants.LOGICAL_NETWORK_SPEED_MAP, portSpeedMap);
                Header[] headers = result
                        .getResponseHeaders(VNXFileConstants.CELERRA_SESSION);
                if (null != headers && headers.length > 0) {
                    keyMap.put(VNXFileConstants.CELERRA_SESSION,
                            headers[0].getValue());
                    _logger.info("Recieved celerra Port Metrics information from the Server.");
                }
            }
        } catch (final Exception ex) {
            _logger.error(
                    "Exception occurred while processing the Port Metrics response due to {}",
                    ex.getMessage());
        } finally {
            result.releaseConnection();
        }

    }

    /**
     * Process the Network device which are received from XMLAPI server.
     * 
     * @param logicalNetworkDevice
     * @param interfacePortMap
     */
    private void processNetworkDevice(LogicalNetworkDevice logicalNetworkDevice,
            Map<String, List<String>> interfacePortMap) {

        // get logical interfaces
        List<String> logicalNetworkList = logicalNetworkDevice.getInterfaces();
        if (logicalNetworkList != null && !logicalNetworkList.isEmpty()) {
            List<String> portList = null;
            for (String interfaceIP : logicalNetworkList) {

                // check interface existing in interfacePortMap
                portList = interfacePortMap.get(interfaceIP);
                if (portList == null) {
                    portList = new ArrayList<String>();
                }

                // if virtual device for logicalNetwork device
                if (logicalNetworkDevice.getType() != LogicalDeviceType.PHYSICAL_ETHERNET) {
                    // virtual device list
                    NameList nameList = logicalNetworkDevice.getVirtualDeviceData().getDevices();
                    if (nameList != null) {
                        // physical port for virtual device list
                        List<String> deviceList = nameList.getLi();
                        if (!deviceList.isEmpty()) {
                            portList.addAll(new ArrayList<>(deviceList));
                            interfacePortMap.put(interfaceIP, portList);
                        }
                    }
                } else {
                    // physical port on LogicalNetworkdevice
                    String networkName = logicalNetworkDevice.getName();
                    if (networkName != null) {
                        portList.add(new String(networkName));
                        interfacePortMap.put(interfaceIP, portList);
                    }
                }
            }
        }
    }

    /**
     * Process the Port speed which are received from XMLAPI server.
     * 
     * @param logicalNetworkDevice
     * @param portSpeedMap
     */
    void processPortSpeed(LogicalNetworkDevice logicalNetworkDevice, Map<String, String> portSpeedMap) {
        List<String> logicalNetworkList = logicalNetworkDevice.getInterfaces();
        if (logicalNetworkList != null && !logicalNetworkList.isEmpty()) {

            // get logical interfaces
            NetworkDeviceSpeed networkDeviceSpeed = logicalNetworkDevice.getSpeed();
            String deviceSpeed = networkDeviceSpeed.value();

            for (String interfaceIP : logicalNetworkList) {
                portSpeedMap.put(interfaceIP, deviceSpeed);
            }

            _logger.info(
                    " logical device port{} and speed : {}", logicalNetworkDevice.getName(), deviceSpeed);
        }
    }
}
