package com.emc.storageos.volumecontroller.impl.block;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.db.client.model.ExportGroup;
import com.emc.storageos.db.client.model.ExportMask;
import com.emc.storageos.db.client.model.Initiator;
import com.emc.storageos.db.client.model.StorageSystem;
import com.emc.storageos.db.client.util.StringSetUtil;
import com.emc.storageos.exceptions.DeviceControllerErrors;
import com.emc.storageos.exceptions.DeviceControllerException;
import com.emc.storageos.util.ExportUtils;
import com.emc.storageos.volumecontroller.BlockStorageDevice;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.ExportOrchestrationTask;
import com.emc.storageos.volumecontroller.impl.utils.ExportMaskUtils;
import com.emc.storageos.workflow.Workflow;
import com.google.common.base.Joiner;

public class CephMaskingOrchestrator extends AbstractBasicMaskingOrchestrator {
    private static final Logger _log = LoggerFactory.getLogger(CephMaskingOrchestrator.class);

    @Override
    public BlockStorageDevice getDevice() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void exportGroupCreate(URI storageURI, URI exportGroupURI, List<URI> initiatorURIs, Map<URI, Integer> volumeMap, String token)
            throws Exception {
        ExportOrchestrationTask taskCompleter = new ExportOrchestrationTask(exportGroupURI, token);
        try {
            ExportGroup exportGroup = _dbClient.queryObject(ExportGroup.class, exportGroupURI);
            StorageSystem storage = _dbClient.queryObject(StorageSystem.class, storageURI);

            if (initiatorURIs != null && !initiatorURIs.isEmpty()) {
                // Set up working flow steps.
                Workflow workflow = _workflowService.getNewWorkflow(
                        MaskingWorkflowEntryPoints.getInstance(), "exportGroupCreate", true, token);

                // Create a mapping of ExportMasks to Add Volumes to or
                // add to a list of new Exports to create
                Map<URI, Map<URI, Integer>> exportMaskToVolumesToAdd = new HashMap<>();
                List<URI> newInitiators = new ArrayList<>();
                List<Initiator> initiators = _dbClient.queryObject(Initiator.class, initiatorURIs);
                for (Initiator initiator : initiators) {
                    List<ExportMask> exportMasks = ExportUtils.getInitiatorExportMasks(initiator, _dbClient);
                    if (exportMasks == null || exportMasks.isEmpty()) {
                        newInitiators.add(initiator.getId());
                    } else {
                        for (ExportMask exportMask : exportMasks) {
                            exportMaskToVolumesToAdd.put(exportMask.getId(), volumeMap);
                        }
                    }
                }

                Map<String, List<URI>> computeResourceToInitiators =
                        mapInitiatorsToComputeResource(exportGroup, newInitiators);
                _log.info(String.format("Need to create ExportMasks for these compute resources %s",
                        Joiner.on(',').join(computeResourceToInitiators.entrySet())));
                // ExportMask that need to be newly create. That is, the initiators in
                // this ExportGroup create do not already exist on the system, hence
                // there aren't any already existing ExportMask for them
                for (Map.Entry<String, List<URI>> toCreate : computeResourceToInitiators.entrySet()) {
                    generateExportMaskCreateWorkflow(workflow, null, storage, exportGroup, toCreate.getValue(), volumeMap, token);
                }

                _log.info(String.format("Need to add volumes for these ExportMasks %s",
                        exportMaskToVolumesToAdd.entrySet()));
                // There are some existing ExportMasks for the initiators in the request.
                // For these, we want to reuse the ExportMask and add volumes to them.
                // These ExportMasks would be created by the system. Ceph has no
                // concept ExportMasks.
                for (Map.Entry<URI, Map<URI, Integer>> toAddVolumes : exportMaskToVolumesToAdd.entrySet()) {
                    ExportMask exportMask = _dbClient.queryObject(ExportMask.class, toAddVolumes.getKey());
                    generateExportMaskAddVolumesWorkflow(workflow, null, storage, exportGroup, exportMask, toAddVolumes.getValue());
                }

                String successMessage = String.format(
                        "ExportGroup successfully applied for StorageArray %s", storage.getLabel());
                workflow.executePlan(taskCompleter, successMessage);
            } else {
                taskCompleter.ready(_dbClient);
            }
        } catch (DeviceControllerException dex) {
            taskCompleter.error(_dbClient, DeviceControllerErrors.ceph.operationFailed("exportGroupCreate", dex.getMessage()));
        } catch (Exception ex) {
            _log.error("ExportGroup Orchestration failed.", ex);
            taskCompleter.error(_dbClient, DeviceControllerErrors.ceph.operationFailed("exportGroupCreate", ex.getMessage()));
        }
    }
    
    @Override
    public void exportGroupDelete(URI storageURI, URI exportGroupURI, String token) throws Exception {
        ExportOrchestrationTask taskCompleter = new ExportOrchestrationTask(exportGroupURI, token);
        try {
            ExportGroup exportGroup = _dbClient.queryObject(ExportGroup.class, exportGroupURI);
            StorageSystem storage = _dbClient.queryObject(StorageSystem.class, storageURI);

            List<ExportMask> masks = ExportMaskUtils.getExportMasks(_dbClient, exportGroup, storageURI);
            if (masks != null && !masks.isEmpty()) {
                Workflow workflow = _workflowService.getNewWorkflow(
                        MaskingWorkflowEntryPoints.getInstance(), "exportGroupDelete", true, token);

                Map<URI, Integer> volumeMap = ExportUtils.getExportGroupVolumeMap(_dbClient, storage, exportGroup);
                List<URI> volumeURIs = new ArrayList<>(volumeMap.keySet());
                List<URI> initiatorURIs = StringSetUtil.stringSetToUriList(exportGroup.getInitiators());
                Map<URI, Map<URI, Integer>> exportMaskToVolumeCount =
                        ExportMaskUtils.mapExportMaskToVolumeShareCount(_dbClient, volumeURIs, initiatorURIs);

                for (ExportMask exportMask : masks) {
                    List<URI> exportGroupURIs = new ArrayList<>();
                    if (!ExportUtils.isExportMaskShared(_dbClient, exportMask.getId(), exportGroupURIs)) {
                        _log.info(String.format("Adding step to delete ExportMask %s", exportMask.getMaskName()));
                        generateExportMaskDeleteWorkflow(workflow, null, storage, exportGroup, exportMask, null);
                    } else {
                        Map<URI, Integer> volumeToExportGroupCount = exportMaskToVolumeCount.get(exportMask.getId());
                        List<URI> volumesToRemove = new ArrayList<>();
                        for (URI uri : volumeMap.keySet()) {
                            if (volumeToExportGroupCount == null) {
                                continue;
                            }
                            // Remove the volume only if it is not shared with
                            // more than 1 ExportGroup
                            Integer numExportGroupsVolumeIsIn = volumeToExportGroupCount.get(uri);
                            if (numExportGroupsVolumeIsIn != null && numExportGroupsVolumeIsIn == 1) {
                                volumesToRemove.add(uri);
                            }
                        }
                        if (!volumesToRemove.isEmpty()) {
                            _log.info(String.format("Adding step to remove volumes %s from ExportMask %s",
                                    Joiner.on(',').join(volumesToRemove), exportMask.getMaskName()));
                            generateExportMaskRemoveVolumesWorkflow(workflow, null, storage, exportGroup, exportMask, volumesToRemove, null);
                        }
                    }
                }

                String successMessage = String.format(
                        "ExportGroup delete successfully completed for StorageArray %s", storage.getLabel());
                workflow.executePlan(taskCompleter, successMessage);
            } else {
                taskCompleter.ready(_dbClient);
            }
        } catch (DeviceControllerException dex) {
            taskCompleter.error(_dbClient, DeviceControllerErrors.ceph.operationFailed("exportGroupDelete", dex.getMessage()));
        } catch (Exception ex) {
            _log.error("ExportGroup Orchestration failed.", ex);
            taskCompleter.error(_dbClient, DeviceControllerErrors.ceph.operationFailed("exportGroupDelete", ex.getMessage()));
        }
    }
    
    @Override
    public void exportGroupAddVolumes(URI storageURI, URI exportGroupURI, Map<URI, Integer> volumeMap, String token) throws Exception {
        ExportOrchestrationTask taskCompleter = new ExportOrchestrationTask(exportGroupURI, token);
        try {
            ExportGroup exportGroup = _dbClient.queryObject(ExportGroup.class, exportGroupURI);
            StorageSystem storage = _dbClient.queryObject(StorageSystem.class, storageURI);

            List<URI> initiatorURIs = StringSetUtil.stringSetToUriList(exportGroup.getInitiators());
            if (initiatorURIs != null && !initiatorURIs.isEmpty()) {
                // Set up workflow steps.
                Workflow workflow = _workflowService.getNewWorkflow(
                        MaskingWorkflowEntryPoints.getInstance(), "exportGroupAddVolumes", true, token);

                // Create a mapping of ExportMasks to Add Volumes to or
                // add to a list of new Exports to create
                Map<URI, Map<URI, Integer>> exportMaskToVolumesToAdd = new HashMap<>();
                List<URI> initiatorsToPlace = new ArrayList<>(initiatorURIs);

                // Need to figure out which ExportMasks to add volumes to.
                for (ExportMask exportMask : ExportMaskUtils.getExportMasks(_dbClient, exportGroup, storageURI)) {
                    if (exportMask.hasAnyInitiators()) {
                        exportMaskToVolumesToAdd.put(exportMask.getId(), volumeMap);
                        for (String uriString : exportMask.getInitiators()) {
                            URI initiatorURI = URI.create(uriString);
                            initiatorsToPlace.remove(initiatorURI);
                        }
                    }
                }

                // ExportMask that need to be newly create because we just added
                // volumes from 'storage' StorageSystem to this ExportGroup
                Map<String, List<URI>> computeResourceToInitiators = mapInitiatorsToComputeResource(exportGroup, initiatorsToPlace);
                if (!computeResourceToInitiators.isEmpty()) {
                    _log.info(String.format("Need to create ExportMasks for these compute resources %s",
                            Joiner.on(',').join(computeResourceToInitiators.entrySet())));                
                    for (Map.Entry<String, List<URI>> toCreate : computeResourceToInitiators.entrySet()) {
                        generateExportMaskCreateWorkflow(workflow, null, storage, exportGroup, toCreate.getValue(), volumeMap, token);
                    }                	
                }

                // We already know about the ExportMask, so we just add volumes to it
                if (!exportMaskToVolumesToAdd.isEmpty()) {
                    _log.info(String.format("Need to add volumes for these ExportMasks %s",
                            exportMaskToVolumesToAdd.entrySet()));
                    for (Map.Entry<URI, Map<URI, Integer>> toAddVolumes : exportMaskToVolumesToAdd.entrySet()) {
                        ExportMask exportMask = _dbClient.queryObject(ExportMask.class, toAddVolumes.getKey());
                        generateExportMaskAddVolumesWorkflow(workflow, null, storage, exportGroup, exportMask, toAddVolumes.getValue());
                    }
                }

                String successMsgTemplate = "ExportGroup add volumes successfully applied for StorageArray %s";
                workflow.executePlan(taskCompleter, String.format(successMsgTemplate, storage.getLabel()));
            } else {
                taskCompleter.ready(_dbClient);
            }
        } catch (DeviceControllerException dex) {
            taskCompleter.error(_dbClient, DeviceControllerErrors.ceph.
            		operationFailed("exportGroupAddVolumes", dex.getMessage()));
        } catch (Exception ex) {
            _log.error("ExportGroup Orchestration failed.", ex);
            taskCompleter.error(_dbClient, DeviceControllerErrors.ceph.
            		operationFailed("exportGroupAddVolumes", ex.getMessage()));
        }
    }

    @Override
    public void exportGroupRemoveVolumes(URI storageURI, URI exportGroupURI, List<URI> volumeURIs, String token) throws Exception {
        ExportOrchestrationTask taskCompleter = new ExportOrchestrationTask(exportGroupURI, token);
        try {
            ExportGroup exportGroup = _dbClient.queryObject(ExportGroup.class, exportGroupURI);
            StorageSystem storage = _dbClient.queryObject(StorageSystem.class, storageURI);

            List<ExportMask> masks = ExportMaskUtils.getExportMasks(_dbClient, exportGroup, storageURI);
            if (masks != null && !masks.isEmpty()) {
                // Set up workflow steps.
                Workflow workflow = _workflowService.getNewWorkflow(
                        MaskingWorkflowEntryPoints.getInstance(), "exportGroupRemoveVolumes", true, token);

                // Generate a mapping of volume URIs to the # of
                // ExportGroups that it is associated with
                List<URI> initiatorURIs = StringSetUtil.stringSetToUriList(exportGroup.getInitiators());
                Map<URI, Map<URI, Integer>> exportMaskToVolumeCount =
                        ExportMaskUtils.mapExportMaskToVolumeShareCount(_dbClient, volumeURIs, initiatorURIs);

                // Generate a mapping of the ExportMask URI to a list volumes to
                // remove from that ExportMask
                Map<URI, List<URI>> exportToRemoveVolumesList = new HashMap<>();
                for (ExportMask exportMask : masks) {
                    Map<URI, Integer> volumeToCountMap = exportMaskToVolumeCount.get(exportMask.getId());
                    if (volumeToCountMap == null) {
                        continue;
                    }
                    for (Map.Entry<URI, Integer> it : volumeToCountMap.entrySet()) {
                        URI volumeURI = it.getKey();
                        Integer numberOfExportGroupsVolumesIsIn = it.getValue();
                        if (numberOfExportGroupsVolumesIsIn == 1) {
                            List<URI> volumesToRemove = exportToRemoveVolumesList.get(exportMask.getId());
                            if (volumesToRemove == null) {
                                volumesToRemove = new ArrayList<>();
                                exportToRemoveVolumesList.put(exportMask.getId(), volumesToRemove);
                            }
                            volumesToRemove.add(volumeURI);
                        }
                    }
                }

                // With the mapping of ExportMask to list of volume URIs,
                // generate a step to remove the volumes from the ExportMask
                for (Map.Entry<URI, List<URI>> entry : exportToRemoveVolumesList.entrySet()) {
                    ExportMask exportMask = _dbClient.queryObject(ExportMask.class, entry.getKey());
                    _log.info(String.format("Adding step to remove volumes %s from ExportMask %s",
                            Joiner.on(',').join(entry.getValue()), exportMask.getMaskName()));
                    generateExportMaskRemoveVolumesWorkflow(workflow, null, storage, exportGroup, exportMask, entry.getValue(), null);
                }

                String successMsgTemplate = "ExportGroup remove volumes successfully applied for StorageArray %s";
                workflow.executePlan(taskCompleter, String.format(successMsgTemplate, storage.getLabel()));
            } else {
                taskCompleter.ready(_dbClient);
            }
        } catch (DeviceControllerException dex) {
            taskCompleter.error(_dbClient, DeviceControllerErrors.ceph.
            		operationFailed("exportGroupRemoveVolumes", dex.getMessage()));
        } catch (Exception ex) {
            _log.error("ExportGroup Orchestration failed.", ex);
            taskCompleter.error(_dbClient, DeviceControllerErrors.ceph.
            		operationFailed("exportGroupRemoveVolumes", ex.getMessage()));
        }
    }    
}
