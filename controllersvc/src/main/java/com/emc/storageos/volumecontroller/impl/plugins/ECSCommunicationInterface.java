/*
 * Copyright (c) 2008-2015 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.volumecontroller.impl.plugins;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.db.client.URIUtil;
import com.emc.storageos.db.client.constraint.AlternateIdConstraint;
import com.emc.storageos.db.client.model.DiscoveredDataObject;
import com.emc.storageos.db.client.model.DiscoveredDataObject.CompatibilityStatus;
import com.emc.storageos.db.client.model.DiscoveredDataObject.DiscoveryStatus;
import com.emc.storageos.db.client.model.DiscoveredDataObject.RegistrationStatus;
import com.emc.storageos.db.client.model.StoragePool;
import com.emc.storageos.db.client.model.StoragePool.PoolServiceType;
import com.emc.storageos.db.client.model.StoragePort;
import com.emc.storageos.db.client.model.StorageSystem;
import com.emc.storageos.db.client.model.StringMap;
import com.emc.storageos.db.client.model.StringSet;
import com.emc.storageos.db.exceptions.DatabaseException;
import com.emc.storageos.ecs.api.ECSApi;
import com.emc.storageos.ecs.api.ECSApiFactory;
import com.emc.storageos.ecs.api.ECSException;
import com.emc.storageos.ecs.api.ECSStoragePool;
import com.emc.storageos.ecs.api.ECSStoragePort;
import com.emc.storageos.plugins.AccessProfile;
import com.emc.storageos.plugins.BaseCollectionException;
import com.emc.storageos.plugins.metering.smis.SMIPluginException;
import com.emc.storageos.svcs.errorhandling.resources.ServiceCode;
import com.emc.storageos.volumecontroller.impl.NativeGUIDGenerator;
import com.emc.storageos.volumecontroller.impl.ecs.ECSCollectionException;
import com.emc.storageos.volumecontroller.impl.utils.ImplicitPoolMatcher;

/**
 * Class for ECS discovery object storage device
 */
public class ECSCommunicationInterface extends ExtendedCommunicationInterfaceImpl {
    private static final int BYTESCONVERTER = 1024;
    private static final String NEW = "new";
    private static final String EXISTING = "existing";
    private static final String ECS_SERIAL_NUM = "0123456789";

    private static final Logger _logger = LoggerFactory.getLogger(ECSCommunicationInterface.class);
    private ECSApiFactory ecsApiFactory;

    /**
     * @param ecsApiFactory the ecsApiFactory to set
     */
    public void setecsApiFactory(ECSApiFactory ecsApiFactory) {
        _logger.info("ECSCommunicationInterface:setecsApiFactory");
        this.ecsApiFactory = ecsApiFactory;
    }

    @Override
    public void collectStatisticsInformation(AccessProfile accessProfile)
            throws BaseCollectionException {
        // TODO Auto-generated method stub

    }

    @Override
    public void scan(AccessProfile accessProfile)
            throws BaseCollectionException {
        // TODO Auto-generated method stub
    }

    /**
     * Get storage pool and storage ports
     */
    @Override
    public void discover(AccessProfile accessProfile)
            throws BaseCollectionException {
        URI storageSystemId = null;
        StorageSystem storageSystem = null;
        String detailedStatusMessage = "Unknown Status";
        long startTime = System.currentTimeMillis();

        _logger.info("ECSCommunicationInterface:discover Access Profile Details :" + accessProfile.toString());
        try {
            storageSystemId = accessProfile.getSystemId();
            storageSystem = _dbClient.queryObject(StorageSystem.class, storageSystemId);

            // try to connect to the ECS
            ECSApi ecsApi = getECSDevice(storageSystem);
            String authToken = ecsApi.getAuthToken();
            if (authToken.isEmpty()) {
                throw ECSException.exceptions.discoverFailed("Could not obtain authToken");
            }

            // Make sure user is system admin before proceeding to discovery
            if (!ecsApi.isSystemAdmin()) {
                _logger.error("User:" + accessProfile.getUserName() + "dont have privileges to access Elastic Cloud Storage: "
                        + accessProfile.getIpAddress());
                _logger.error("Discovery failed");
                throw ECSException.exceptions.discoverFailed("User is not ECS System Admin");
            }

            // Get details of storage system
            String nativeGuid = NativeGUIDGenerator.generateNativeGuid(DiscoveredDataObject.Type.ecs.toString(),
                    ECS_SERIAL_NUM);
            storageSystem.setNativeGuid(nativeGuid);
            storageSystem.setSerialNumber(nativeGuid); // No serial num API exposed
            storageSystem.setUsername(accessProfile.getUserName());
            storageSystem.setPassword(accessProfile.getPassword());
            storageSystem.setPortNumber(accessProfile.getPortNumber());
            storageSystem.setIpAddress(accessProfile.getIpAddress());
            storageSystem.setCompatibilityStatus(DiscoveredDataObject.CompatibilityStatus.COMPATIBLE.name());
            storageSystem.setReachableStatus(true);
            storageSystem.setInactive(false);
            _dbClient.persistObject(storageSystem);

            // Discover storage pools
            Map<String, List<StoragePool>> allPools = new HashMap<String, List<StoragePool>>();
            List<StoragePool> newPools = new ArrayList<StoragePool>();
            List<StoragePool> existingPools = new ArrayList<StoragePool>();
            StoragePool storagePool;

            List<ECSStoragePool> ecsStoragePools = ecsApi.getStoragePools();

            for (ECSStoragePool ecsPool : ecsStoragePools) {
                // Check if this storage pool was already discovered
                storagePool = null;
                String storagePoolNativeGuid = NativeGUIDGenerator.generateNativeGuid(
                        storageSystem, ecsPool.getId(), NativeGUIDGenerator.POOL);
                @SuppressWarnings("deprecation")
                List<URI> poolURIs = _dbClient.queryByConstraint(AlternateIdConstraint.Factory.
                        getStoragePoolByNativeGuidConstraint(storagePoolNativeGuid));

                for (URI poolUri : poolURIs) {
                    StoragePool pool = _dbClient.queryObject(StoragePool.class, poolUri);
                    if (!pool.getInactive() && pool.getStorageDevice().equals(storageSystemId)) {
                        storagePool = pool;
                        break;
                    }
                }

                if (storagePool == null) {
                    storagePool = new StoragePool();
                    storagePool.setId(URIUtil.createId(StoragePool.class));
                    storagePool.setNativeId(ecsPool.getId());
                    storagePool.setNativeGuid(storagePoolNativeGuid);
                    storagePool.setLabel(storagePoolNativeGuid);
                    storagePool.setPoolClassName("ECS Pool");
                    storagePool.setStorageDevice(storageSystem.getId());
                    StringSet protocols = new StringSet();
                    protocols.add("S3");
                    protocols.add("Swift");
                    protocols.add("Atmos");
                    storagePool.setProtocols(protocols);
                    storagePool.setPoolName(ecsPool.getName());
                    storagePool.setOperationalStatus(StoragePool.PoolOperationalStatus.READY.toString());
                    storagePool.setPoolServiceType(PoolServiceType.object.toString());
                    storagePool.setRegistrationStatus(RegistrationStatus.REGISTERED.toString());
                    storagePool.setSupportedResourceTypes(StoragePool.SupportedResourceTypes.THICK_ONLY.toString());
                    storagePool.setFreeCapacity(ecsPool.getFreeCapacity() * BYTESCONVERTER * BYTESCONVERTER);
                    storagePool.setTotalCapacity(ecsPool.getTotalCapacity() * BYTESCONVERTER * BYTESCONVERTER);
                    storagePool.setInactive(false);
                    _logger.info("Creating new ECS storage pool using NativeId : {}", storagePoolNativeGuid);
                    storagePool.setDiscoveryStatus(DiscoveryStatus.VISIBLE.name());
                    storagePool.setCompatibilityStatus(DiscoveredDataObject.CompatibilityStatus.COMPATIBLE.name());
                    newPools.add(storagePool);
                }
                else {
                    existingPools.add(storagePool);
                }
            }
            allPools.put(NEW, newPools);
            allPools.put(EXISTING, existingPools);
            _logger.info("No of newly discovered pools {}", allPools.get(NEW).size());
            _logger.info("No of existing discovered pools {}", allPools.get(EXISTING).size());

            if (!allPools.get(NEW).isEmpty()) {
                _dbClient.createObject(allPools.get(NEW));
            }

            if (!allPools.get(EXISTING).isEmpty()) {
                _dbClient.persistObject(allPools.get(EXISTING));
            }

            // Get storage ports
            HashMap<String, List<StoragePort>> storagePorts = new HashMap<String, List<StoragePort>>();
            List<StoragePort> newStoragePorts = new ArrayList<StoragePort>();
            List<StoragePort> existingStoragePorts = new ArrayList<StoragePort>();
            List<ECSStoragePort> ecsStoragePorts = ecsApi.getStoragePort(storageSystem.getIpAddress());

            for (ECSStoragePort ecsPort : ecsStoragePorts) {
                StoragePort storagePort = null;

                String portNativeGuid = NativeGUIDGenerator.generateNativeGuid(
                        storageSystem, ecsPort.getIpAddress(),
                        NativeGUIDGenerator.PORT);
                // Check if storage port was already discovered
                @SuppressWarnings("deprecation")
                List<URI> portURIs = _dbClient.queryByConstraint(AlternateIdConstraint.Factory.
                        getStoragePortByNativeGuidConstraint(portNativeGuid));
                for (URI portUri : portURIs) {
                    StoragePort port = _dbClient.queryObject(StoragePort.class, portUri);
                    if (port.getStorageDevice().equals(storageSystemId) && !port.getInactive()) {
                        storagePort = port;
                        break;
                    }
                }

                if (storagePort == null) {
                    // Create new port
                    storagePort = new StoragePort();
                    storagePort.setId(URIUtil.createId(StoragePort.class));
                    storagePort.setTransportType("IP");
                    storagePort.setNativeGuid(portNativeGuid);
                    storagePort.setLabel(portNativeGuid);
                    storagePort.setStorageDevice(storageSystemId);
                    storagePort.setPortNetworkId(ecsPort.getIpAddress().toLowerCase());
                    storagePort.setPortName(ecsPort.getName());
                    storagePort.setLabel(ecsPort.getName());
                    storagePort.setPortGroup(ecsPort.getName());
                    storagePort.setRegistrationStatus(RegistrationStatus.REGISTERED.toString());
                    storagePort.setOperationalStatus(StoragePort.OperationalStatus.OK.toString());
                    _logger.info("Creating new storage port using NativeGuid : {}", portNativeGuid);
                    newStoragePorts.add(storagePort);
                } else {
                    existingStoragePorts.add(storagePort);
                }
                storagePort.setDiscoveryStatus(DiscoveryStatus.VISIBLE.name());
                storagePort.setCompatibilityStatus(DiscoveredDataObject.CompatibilityStatus.COMPATIBLE.name());
            }
            storagePorts.put(NEW, newStoragePorts);
            storagePorts.put(EXISTING, existingStoragePorts);

            _logger.info("No of newly discovered ports {}", storagePorts.get(NEW).size());
            _logger.info("No of existing discovered ports {}", storagePorts.get(EXISTING).size());
            if (!storagePorts.get(NEW).isEmpty()) {
                _dbClient.createObject(storagePorts.get(NEW));
            }

            if (!storagePorts.get(EXISTING).isEmpty()) {
                _dbClient.persistObject(storagePorts.get(EXISTING));
            }

            //Discovery success
            detailedStatusMessage = String.format("Discovery completed successfully for ECS: %s",
                    storageSystemId.toString());
        } catch (Exception e) {
            if (storageSystem != null) {
                cleanupDiscovery(storageSystem);
            }
            detailedStatusMessage = String.format("Discovery failed for Storage System ECS %s: because %s",
                    storageSystemId.toString(), e.getLocalizedMessage());
            _logger.error(detailedStatusMessage, e);
            throw new ECSCollectionException(false, ServiceCode.DISCOVERY_ERROR,
                    null, detailedStatusMessage, null, null);
        } finally {
            if (storageSystem != null) {
                try {
                    // set detailed message
                    storageSystem.setLastDiscoveryStatusMessage(detailedStatusMessage);
                    _dbClient.persistObject(storageSystem);
                } catch (DatabaseException ex) {
                    _logger.error("Error while persisting object to DB", ex);
                }
            }

            long totalTime = System.currentTimeMillis() - startTime;
            _logger.info(String.format("Discovery of ECS Storage System %s took %f seconds", accessProfile.getIpAddress(),
                    (double) totalTime
                            / (double) 1000));
        }
    }

    /**
     * Get ecs device represented by the StorageDevice
     *
     * @param ecsCluster StorageDevice object
     * @return ECSApi object
     * @throws ECSException
     * @throws URISyntaxException
     */
    private ECSApi getECSDevice(StorageSystem ecsSystem) throws ECSException, URISyntaxException {
        URI deviceURI = new URI("https", null, ecsSystem.getIpAddress(), ecsSystem.getPortNumber(), "/", null, null);

        return ecsApiFactory
                .getRESTClient(deviceURI, ecsSystem.getUsername(), ecsSystem.getPassword());
    }

    /**
     * If discovery fails, then mark the system as unreachable.
     *
     * @param system  the system that failed discovery.
     */
    private void cleanupDiscovery(StorageSystem system) {
        try {
            system.setReachableStatus(false);
            _dbClient.persistObject(system);
        } catch (DatabaseException e) {
            _logger.error("discoverStorage failed.  Failed to update discovery status to ERROR.", e);
        }

    }

}
