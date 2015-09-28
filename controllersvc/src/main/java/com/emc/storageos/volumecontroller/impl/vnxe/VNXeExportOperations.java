/*
 * Copyright (c) 2014 EMC Corporation
 * All Rights Reserved
 */

package com.emc.storageos.volumecontroller.impl.vnxe;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.db.client.URIUtil;
import com.emc.storageos.db.client.model.BlockObject;
import com.emc.storageos.db.client.model.BlockSnapshot;
import com.emc.storageos.db.client.model.ExportGroup;
import com.emc.storageos.db.client.model.ExportMask;
import com.emc.storageos.db.client.model.HostInterface.Protocol;
import com.emc.storageos.db.client.model.Initiator;
import com.emc.storageos.db.client.model.StorageSystem;
import com.emc.storageos.db.client.model.StringSet;
import com.emc.storageos.db.client.model.VirtualPool;
import com.emc.storageos.db.client.model.Volume;
import com.emc.storageos.exceptions.DeviceControllerErrors;
import com.emc.storageos.exceptions.DeviceControllerException;
import com.emc.storageos.svcs.errorhandling.model.ServiceError;
import com.emc.storageos.vnxe.VNXeApiClient;
import com.emc.storageos.vnxe.models.VNXeExportResult;
import com.emc.storageos.vnxe.models.VNXeHostInitiator;
import com.emc.storageos.vnxe.models.VNXeLunSnap;
import com.emc.storageos.volumecontroller.TaskCompleter;
import com.emc.storageos.volumecontroller.impl.VolumeURIHLU;
import com.emc.storageos.volumecontroller.impl.smis.ExportMaskOperations;
import com.emc.storageos.volumecontroller.impl.utils.ExportMaskUtils;

public class VNXeExportOperations extends VNXeOperations implements ExportMaskOperations {
    private static final Logger _logger = LoggerFactory.getLogger(VNXeExportOperations.class);

    @Override
    public void createExportMask(StorageSystem storage, URI exportMask,
            VolumeURIHLU[] volumeURIHLUs, List<URI> targetURIList,
            List<Initiator> initiatorList, TaskCompleter taskCompleter)
            throws DeviceControllerException {
        _logger.info("Creating export mask.");
        VNXeApiClient apiClient = getVnxeClient(storage);
        try {
            List<VNXeHostInitiator> initiators = prepareInitiators(initiatorList);
            ExportMask mask = _dbClient.queryObject(ExportMask.class, exportMask);
            for (VolumeURIHLU volURIHLU : volumeURIHLUs) {
                URI volUri = volURIHLU.getVolumeURI();
                BlockObject blockObject = BlockObject.fetch(_dbClient, volUri);
                String nativeId = blockObject.getNativeId();
                if (URIUtil.isType(volUri, Volume.class)) {
                    VNXeExportResult result = apiClient.exportLun(nativeId, initiators);
                    mask.addVolume(volUri, result.getHlu());
                } else if (URIUtil.isType(volUri, BlockSnapshot.class)) {
                    VNXeExportResult result = apiClient.exportSnap(nativeId, initiators);
                    setSnapWWN(apiClient, blockObject, nativeId);
                    mask.addVolume(volUri, result.getHlu());
                }

            }
            _dbClient.persistObject(mask);
            taskCompleter.ready(_dbClient);

        } catch (Exception e) {
            _logger.error("Unexpected error: createExportMask failed.", e);
            ServiceError error = DeviceControllerErrors.vnxe.jobFailed("createExportMask", e.getMessage());
            taskCompleter.error(_dbClient, error);
        }

    }

    private List<VNXeHostInitiator> prepareInitiators(List<Initiator> initiators) {
        List<VNXeHostInitiator> result = new ArrayList<VNXeHostInitiator>();
        for (Initiator init : initiators) {
            _logger.info("initiator: {}", init.getId().toString());
            VNXeHostInitiator hostInit = new VNXeHostInitiator();
            hostInit.setName(init.getHostName());
            String protocol = init.getProtocol();
            if (protocol.equalsIgnoreCase(Protocol.iSCSI.name())) {
                hostInit.setType(VNXeHostInitiator.HostInitiatorTypeEnum.INITIATOR_TYPE_ISCSI);
                hostInit.setChapUserName(init.getInitiatorPort());
                hostInit.setInitiatorId(init.getInitiatorPort());

            } else if (protocol.equalsIgnoreCase(Protocol.FC.name())) {
                hostInit.setType(VNXeHostInitiator.HostInitiatorTypeEnum.INITIATOR_TYPE_FC);
                String portWWN = init.getInitiatorPort();
                String nodeWWN = init.getInitiatorNode();
                StringBuilder builder = new StringBuilder(nodeWWN);
                builder.append(":");
                builder.append(portWWN);
                hostInit.setInitiatorId(builder.toString());
                hostInit.setNodeWWN(nodeWWN);
                hostInit.setPortWWN(portWWN);
            } else {
                _logger.info("The initiator {} protocol {} is not supported, skip",
                        init.getId(), init.getProtocol());
                continue;
            }

            hostInit.setName(init.getHostName());
            result.add(hostInit);

        }
        return result;
    }

    @Override
    public void deleteExportMask(StorageSystem storage, URI exportMaskUri,
            List<URI> volumeURIList, List<URI> targetURIList,
            List<Initiator> initiatorList, TaskCompleter taskCompleter)
            throws DeviceControllerException {
        _logger.info("{} deleteExportMask START...", storage.getSerialNumber());

        try {
            VNXeApiClient apiClient = getVnxeClient(storage);
            ExportMask exportMask = _dbClient.queryObject(ExportMask.class, exportMaskUri);
            StringSet inits = exportMask.getInitiators();
            for (String init : inits) {
                _logger.info("Initiator: {}", init);
                Initiator initiator = _dbClient.queryObject(Initiator.class, URI.create(init));
                initiatorList.add(initiator);
            }

            List<VNXeHostInitiator> initiators = prepareInitiators(initiatorList);
            for (URI volUri : volumeURIList) {
                BlockObject blockObject = BlockObject.fetch(_dbClient, volUri);
                String nativeId = blockObject.getNativeId();
                if (URIUtil.isType(volUri, Volume.class)) {
                    apiClient.unexportLun(nativeId, initiators);
                } else if (URIUtil.isType(volUri, BlockSnapshot.class)) {
                    apiClient.unexportSnap(nativeId, initiators);
                    setSnapWWN(apiClient, blockObject, nativeId);
                }
                // update the exportMask object
                exportMask.removeVolume(volUri);
            }

            _dbClient.updateAndReindexObject(exportMask);

            List<ExportGroup> exportGroups = ExportMaskUtils.getExportGroups(_dbClient, exportMask);
            if (exportGroups != null) {
                // Remove the mask references in the export group
                for (ExportGroup exportGroup : exportGroups) {
                    // Remove this mask from the export group
                    exportGroup.removeExportMask(exportMask.getId().toString());
                }
                // Update all of the export groups in the DB
                _dbClient.updateAndReindexObject(exportGroups);
            }

            taskCompleter.ready(_dbClient);
        } catch (Exception e) {
            _logger.error("Unexpected error: deleteExportMask failed.", e);
            ServiceError error = DeviceControllerErrors.vnxe.jobFailed("deleteExportMask", e.getMessage());
            taskCompleter.error(_dbClient, error);
        }
        _logger.info("{} deleteExportMask END...", storage.getSerialNumber());
    }

    @Override
    public void addVolume(StorageSystem storage, URI exportMaskUri,
            VolumeURIHLU[] volumeURIHLUs, TaskCompleter taskCompleter)
            throws DeviceControllerException {
        _logger.info("{} addVolume START...", storage.getSerialNumber());
        try {
            VNXeApiClient apiClient = getVnxeClient(storage);
            ExportMask exportMask = _dbClient.queryObject(ExportMask.class, exportMaskUri);
            StringSet initiatorUris = exportMask.getInitiators();
            List<Initiator> initiators = new ArrayList<Initiator>();
            for (String initiatorUri : initiatorUris) {
                Initiator init = _dbClient.queryObject(Initiator.class, URI.create(initiatorUri));
                initiators.add(init);
            }
            List<VNXeHostInitiator> vnxeInitiators = prepareInitiators(initiators);

            for (VolumeURIHLU volURIHLU : volumeURIHLUs) {
                URI volUri = volURIHLU.getVolumeURI();
                BlockObject blockObject = BlockObject.fetch(_dbClient, volUri);
                String nativeId = blockObject.getNativeId();
                if (URIUtil.isType(volUri, Volume.class)) {
                    VNXeExportResult result = apiClient.exportLun(nativeId, vnxeInitiators);
                    exportMask.addVolume(volUri, result.getHlu());
                } else if (URIUtil.isType(volUri, BlockSnapshot.class)) {
                    VNXeExportResult result = apiClient.exportSnap(nativeId, vnxeInitiators);
                    exportMask.addVolume(volUri, result.getHlu());
                    setSnapWWN(apiClient, blockObject, nativeId);
                }

            }
            _dbClient.persistObject(exportMask);
            taskCompleter.ready(_dbClient);

        } catch (Exception e) {
            _logger.error("Add volumes error: ", e);
            ServiceError error = DeviceControllerErrors.vnxe.jobFailed("addVolume", e.getMessage());
            taskCompleter.error(_dbClient, error);
        }

    }

    @Override
    public void removeVolume(StorageSystem storage, URI exportMaskUri,
            List<URI> volumes, TaskCompleter taskCompleter)
            throws DeviceControllerException {
        _logger.info("{} remove volume START...", storage.getSerialNumber());

        try {
            VNXeApiClient apiClient = getVnxeClient(storage);
            ExportMask exportMask = _dbClient.queryObject(ExportMask.class, exportMaskUri);
            StringSet initiatorUris = exportMask.getInitiators();
            List<Initiator> initiators = new ArrayList<Initiator>();
            for (String initiatorUri : initiatorUris) {
                Initiator init = _dbClient.queryObject(Initiator.class, URI.create(initiatorUri));
                initiators.add(init);
            }
            List<VNXeHostInitiator> vnxeInitiators = prepareInitiators(initiators);
            for (URI volUri : volumes) {
                BlockObject blockObject = BlockObject.fetch(_dbClient, volUri);
                String nativeId = blockObject.getNativeId();
                if (URIUtil.isType(volUri, Volume.class)) {
                    apiClient.unexportLun(nativeId, vnxeInitiators);
                } else if (URIUtil.isType(volUri, BlockSnapshot.class)) {
                    apiClient.unexportSnap(nativeId, vnxeInitiators);
                    setSnapWWN(apiClient, blockObject, nativeId);
                }
                // update the exportMask object
                exportMask.removeVolume(volUri);
            }

            _dbClient.updateAndReindexObject(exportMask);

            taskCompleter.ready(_dbClient);
        } catch (Exception e) {
            _logger.error("Unexpected error: removeVolume failed.", e);
            ServiceError error = DeviceControllerErrors.vnxe.jobFailed("remove volume failed", e.getMessage());
            taskCompleter.error(_dbClient, error);
        }
        _logger.info("{} remove volume END...", storage.getSerialNumber());
    }

    @Override
    public void addInitiator(StorageSystem storage, URI exportMaskUri,
            List<Initiator> initiatorList, List<URI> targets,
            TaskCompleter taskCompleter) throws DeviceControllerException {

        _logger.info("{} addInitiator START...", storage.getSerialNumber());

    }

    @Override
    public void removeInitiator(StorageSystem storage, URI exportMask,
            List<Initiator> initiators, List<URI> targets,
            TaskCompleter taskCompleter) throws DeviceControllerException {
        // TODO Auto-generated method stub

    }

    @Override
    public Map<String, Set<URI>> findExportMasks(StorageSystem storage,
            List<String> initiatorNames, boolean mustHaveAllPorts) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public ExportMask refreshExportMask(StorageSystem storage, ExportMask mask) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void updateStorageGroupPolicyAndLimits(StorageSystem storage, ExportMask exportMask,
            List<URI> volumeURIs, VirtualPool newVirtualPool, boolean rollback,
            TaskCompleter taskCompleter) throws Exception {
        throw DeviceControllerException.exceptions.blockDeviceOperationNotSupported();
    }

    @Override
    public Map<URI, Integer> getExportMaskHLUs(StorageSystem storage, ExportMask exportMask) {
        return Collections.emptyMap();
    }

    /**
     * set snap wwn after export/unexport. if a snap is not exported to any host, its wwn is null
     * 
     * @param apiClient
     * @param blockObj
     * @param snapId
     */
    private void setSnapWWN(VNXeApiClient apiClient, BlockObject blockObj, String snapId) {

        VNXeLunSnap snap = apiClient.getLunSnapshot(snapId);
        String wwn = snap.getPromotedWWN();
        if (wwn == null) {
            wwn = "";
        }
        blockObj.setWWN(wwn);
        _dbClient.persistObject(blockObj);
    }

}
