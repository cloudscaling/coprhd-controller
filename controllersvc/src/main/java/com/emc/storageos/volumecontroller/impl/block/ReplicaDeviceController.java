/*
 * Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.volumecontroller.impl.block;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.Controller;
import com.emc.storageos.blockorchestrationcontroller.BlockOrchestrationInterface;
import com.emc.storageos.blockorchestrationcontroller.VolumeDescriptor;
import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.URIUtil;
import com.emc.storageos.db.client.constraint.AlternateIdConstraint;
import com.emc.storageos.db.client.constraint.ContainmentConstraint;
import com.emc.storageos.db.client.constraint.URIQueryResultList;
import com.emc.storageos.db.client.model.BlockMirror;
import com.emc.storageos.db.client.model.BlockSnapshot;
import com.emc.storageos.db.client.model.NamedURI;
import com.emc.storageos.db.client.model.OpStatusMap;
import com.emc.storageos.db.client.model.StorageSystem;
import com.emc.storageos.db.client.model.StringSet;
import com.emc.storageos.db.client.model.Volume;
import com.emc.storageos.db.client.util.NullColumnValueGetter;
import com.emc.storageos.db.client.util.ResourceOnlyNameGenerator;
import com.emc.storageos.exceptions.DeviceControllerException;
import com.emc.storageos.svcs.errorhandling.model.ServiceError;
import com.emc.storageos.svcs.errorhandling.resources.InternalException;
import com.emc.storageos.volumecontroller.ControllerException;
import com.emc.storageos.volumecontroller.TaskCompleter;
import com.emc.storageos.volumecontroller.impl.ControllerUtils;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.BlockConsistencyGroupUpdateCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.BlockSnapshotRestoreCompleter;
import com.emc.storageos.volumecontroller.impl.block.taskcompleter.VolumeWorkflowCompleter;
import com.emc.storageos.volumecontroller.impl.smis.SmisConstants;
import com.emc.storageos.workflow.Workflow;
import com.emc.storageos.workflow.WorkflowException;
import com.emc.storageos.workflow.WorkflowStepCompleter;
import com.google.common.base.Joiner;

/**
 * Specific controller implementation to support block orchestration for handling replicas of volumes in a consistency group.
 */
public class ReplicaDeviceController implements Controller, BlockOrchestrationInterface {
    private static final Logger log = LoggerFactory.getLogger(ReplicaDeviceController.class);
    private DbClient _dbClient;
    private BlockDeviceController _blockDeviceController;

    public void setDbClient(DbClient dbClient) {
        _dbClient = dbClient;
    }

    public void setBlockDeviceController(BlockDeviceController blockDeviceController) {
        this._blockDeviceController = blockDeviceController;
    }

    @Override
    public String addStepsForCreateVolumes(Workflow workflow, String waitFor, List<VolumeDescriptor> volumes,
            String taskId) throws InternalException {
        // Get the list of descriptors which represent source volumes that have
        // just been created and added to CG possibly
        List<VolumeDescriptor> volumeDescriptors = VolumeDescriptor.filterByType(volumes,
                new VolumeDescriptor.Type[] { VolumeDescriptor.Type.BLOCK_DATA, VolumeDescriptor.Type.SRDF_SOURCE,
                        VolumeDescriptor.Type.SRDF_EXISTING_SOURCE }, null);

        // If no source volumes, just return
        if (volumeDescriptors.isEmpty()) {
            log.info("No replica steps required");
            return waitFor;
        }

        // Get the consistency group. If no consistency group for source
        // volumes,
        // just return. Get CG from any descriptor.
        URI cgURI = null;
        final VolumeDescriptor firstVolumeDescriptor = volumeDescriptors.get(0);
        if (firstVolumeDescriptor != null) {
            Volume volume = _dbClient.queryObject(Volume.class, firstVolumeDescriptor.getVolumeURI());
            if (!(volume != null && volume.isInCG() && ControllerUtils.isVmaxVolumeUsing803SMIS(volume, _dbClient))) {
                return waitFor;
            }
            log.info("CG URI:{}", volume.getConsistencyGroup());
            cgURI = volume.getConsistencyGroup();
        }

        List<VolumeDescriptor> nonSrdfVolumeDescriptors = VolumeDescriptor.filterByType(volumes,
                new VolumeDescriptor.Type[] { VolumeDescriptor.Type.BLOCK_DATA }, null);

        if (nonSrdfVolumeDescriptors != null && !nonSrdfVolumeDescriptors.isEmpty()) {
            waitFor = createReplicaIfCGHasReplica(workflow, waitFor,
                    nonSrdfVolumeDescriptors, cgURI);
        } else {
            // Create Replica for SRDF R1 and R2 if any replica available already
            List<VolumeDescriptor> srdfSourceVolumeDescriptors = VolumeDescriptor.filterByType(volumes,
                    new VolumeDescriptor.Type[] { VolumeDescriptor.Type.SRDF_SOURCE,
                            VolumeDescriptor.Type.SRDF_EXISTING_SOURCE }, null);
            log.debug("srdfSourceVolumeDescriptors :{}", srdfSourceVolumeDescriptors);
            List<VolumeDescriptor> srdfTargetVolumeDescriptors = VolumeDescriptor.filterByType(volumes,
                    new VolumeDescriptor.Type[] { VolumeDescriptor.Type.SRDF_TARGET }, null);
            log.debug("srdfTargetVolumeDescriptors :{}", srdfTargetVolumeDescriptors);
            // Create replica for R1
            waitFor = createReplicaIfCGHasReplica(workflow, waitFor,
                    srdfSourceVolumeDescriptors, cgURI);

            // get target CG
            // New Target Volume Descriptors and Volume objects will not have CG URI set
            final URIQueryResultList uriQueryResultList = new URIQueryResultList();
            _dbClient.queryByConstraint(AlternateIdConstraint.Factory
                    .getBlockObjectsByConsistencyGroup(cgURI.toString()),
                    uriQueryResultList);
            Iterator<URI> volumeItr = uriQueryResultList.iterator();
            List<URI> newSourceVolumes = new ArrayList<URI>();
            for (VolumeDescriptor volumeDesc : srdfSourceVolumeDescriptors) {
                newSourceVolumes.add(volumeDesc.getVolumeURI());
            }
            URI targetVolumeCGURI = null;
            while (volumeItr.hasNext()) {
                URI volumeURI = volumeItr.next();
                if (!newSourceVolumes.contains(volumeURI)) {
                    Volume existingSourceVolume = _dbClient.queryObject(Volume.class, volumeURI);
                    Volume existingTargetVolume = null;
                    // get target
                    StringSet targets = existingSourceVolume.getSrdfTargets();
                    for (String target : targets) {
                        if (NullColumnValueGetter.isNotNullValue(target)) {
                            existingTargetVolume = _dbClient.queryObject(Volume.class, URI.create(target));
                            targetVolumeCGURI = existingTargetVolume.getConsistencyGroup();
                            break;
                        }
                    }
                    break;
                }
            }

            waitFor = createReplicaIfCGHasReplica(workflow, waitFor,
                    srdfTargetVolumeDescriptors, targetVolumeCGURI);

        }

        return waitFor;
    }

    /**
     * Creates replica snap/clone/mirror for the newly created volume, if the existing CG Volume has any replica.
     * 
     * @param workflow
     * @param waitFor
     * @param volumeDescriptors
     * @param cgURI
     * @return
     */
    private String createReplicaIfCGHasReplica(Workflow workflow,
            String waitFor, List<VolumeDescriptor> volumeDescriptors, URI cgURI) {
        log.info("CG URI {}", cgURI);
        if (volumeDescriptors != null && !volumeDescriptors.isEmpty()) {
            VolumeDescriptor firstVolumeDescriptor = volumeDescriptors.get(0);
            if (firstVolumeDescriptor != null && cgURI != null) {
                // find member volumes in the group
                List<Volume> volumeList = ControllerUtils.getVolumesPartOfCG(cgURI, _dbClient);
                if (checkIfCGHasCloneReplica(volumeList)) {
                    log.info("Adding clone steps for create {} volumes", firstVolumeDescriptor.getType());
                    // create new clones for the newly created volumes
                    // add the created clones to clone groups
                    waitFor = createCloneSteps(workflow, waitFor, volumeDescriptors, volumeList, cgURI);
                }

                if (checkIfCGHasMirrorReplica(volumeList)) {
                    log.info("Adding mirror steps for create {} volumes", firstVolumeDescriptor.getType());
                    // create new mirrors for the newly created volumes
                    // add the created mirrors to mirror groups
                    waitFor = createMirrorSteps(workflow, waitFor, volumeDescriptors, volumeList, cgURI);
                }

                if (checkIfCGHasSnapshotReplica(volumeList)) {
                    log.info("Adding snapshot steps for create {} volumes", firstVolumeDescriptor.getType());
                    // create new snapshots for the newly created volumes
                    // add the created snapshots to snapshot groups
                    waitFor = createSnapshotSteps(workflow, waitFor, volumeDescriptors, volumeList, cgURI);
                }
            }
        }

        return waitFor;
    }

    /*
     * 1. for each newly created volumes in a CG, create a snapshot
     * 2. add all snpshots to an existing replication group.
     */
    private String createSnapshotSteps(Workflow workflow, String waitFor, List<VolumeDescriptor> volumeDescriptors,
            List<Volume> volumeList, URI cgURI) {
        log.info("START create snapshot steps");
        List<URI> sourceList = VolumeDescriptor.getVolumeURIs(volumeDescriptors);
        List<Volume> volumes = _dbClient.queryObject(Volume.class, sourceList);
        URI storage = volumes.get(0).getStorageController();
        StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, storage);

        Set<String> repGroupNames = ControllerUtils.getSnapshotReplicationGroupNames(volumeList, _dbClient);
        if (repGroupNames.isEmpty()) {
            return waitFor;
        }

        for (String repGroupName : repGroupNames) {
            waitFor = addSnapshotsToReplicationGroupStep(workflow, waitFor, storageSystem, volumes,
                    repGroupName, cgURI);
        }

        return waitFor;
    }

    /*
     * 1. for each newly created volumes in a CG, create a clone
     * 2. add all clones to an existing replication group
     */
    private String createCloneSteps(final Workflow workflow, String waitFor,
            final List<VolumeDescriptor> volumeDescriptors, List<Volume> volumeList, URI cgURI) {
        log.info("START create clone steps");
        List<URI> sourceList = VolumeDescriptor.getVolumeURIs(volumeDescriptors);
        List<Volume> volumes = _dbClient.queryObject(Volume.class, sourceList);
        URI storage = volumes.get(0).getStorageController();
        StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, storage);

        Set<String> repGroupNames = ControllerUtils.getCloneReplicationGroupNames(volumeList, _dbClient);
        if (repGroupNames.isEmpty()) {
            return waitFor;
        }

        for (String repGroupName : repGroupNames) {
            waitFor = addClonesToReplicationGroupStep(workflow, waitFor, storageSystem, volumes, repGroupName, cgURI);
        }

        return waitFor;
    }

    private String addSnapshotsToReplicationGroupStep(final Workflow workflow, String waitFor,
            StorageSystem storageSystem,
            List<Volume> volumes,
            String repGroupName, URI cgURI) {
        log.info("START create snapshot step");
        URI storage = storageSystem.getId();
        List<URI> snapshotList = new ArrayList<>();
        for (Volume volume : volumes) {
            BlockSnapshot snapshot = prepareSnapshot(volume, repGroupName);
            URI snapshotId = snapshot.getId();
            snapshotList.add(snapshotId);

            Workflow.Method createMethod = new Workflow.Method(
                    BlockDeviceController.CREATE_SINGLE_SNAPSHOT_METHOD, storage, snapshotList, false, false);
            workflow.createStep(BlockDeviceController.CREATE_SNAPSHOTS_STEP_GROUP,
                    "Create snapshot", waitFor, storage, storageSystem.getSystemType(),
                    _blockDeviceController.getClass(),
                    createMethod, _blockDeviceController.rollbackMethodNullMethod(), null);
        }
        waitFor = BlockDeviceController.CREATE_SNAPSHOTS_STEP_GROUP;

        waitFor = workflow.createStep(BlockDeviceController.UPDATE_CONSISTENCY_GROUP_STEP_GROUP,
                String.format("Updating consistency group  %s", cgURI), waitFor, storage,
                _blockDeviceController.getDeviceType(storage), this.getClass(),
                addToReplicationGroupMethod(storage, cgURI, repGroupName, snapshotList),
                _blockDeviceController.rollbackMethodNullMethod(), null);
        log.info(String.format("Step created for adding snapshot [%s] to group on device [%s]",
                Joiner.on("\t").join(snapshotList), storage));

        return waitFor;
    }

    private String addClonesToReplicationGroupStep(final Workflow workflow, String waitFor, StorageSystem storageSystem,
            List<Volume> volumes, String repGroupName, URI cgURI) {
        log.info("START create clone step");
        URI storage = storageSystem.getId();
        List<URI> cloneList = new ArrayList<URI>();
        for (Volume volume : volumes) {
            Volume clone = prepareClone(volume, repGroupName);
            URI cloneId = clone.getId();
            cloneList.add(cloneId);
            // TODO - use CreateListReplica to create clones of all volumes in one call
            waitFor = _blockDeviceController.createSingleCloneStep(workflow, storage, storageSystem, volume, cloneId, waitFor);
        }

        waitFor = BlockDeviceController.FULL_COPY_WFS_STEP_GROUP;

        waitFor = workflow.createStep(BlockDeviceController.UPDATE_CONSISTENCY_GROUP_STEP_GROUP,
                String.format("Updating consistency group  %s", cgURI), waitFor, storage,
                _blockDeviceController.getDeviceType(storage), this.getClass(),
                addToReplicationGroupMethod(storage, cgURI, repGroupName, cloneList),
                _blockDeviceController.rollbackMethodNullMethod(), null);
        log.info(String.format("Step created for adding clone [%s] to group on device [%s]",
                Joiner.on("\t").join(cloneList), storage));

        return waitFor;
    }

    private BlockSnapshot prepareSnapshot(Volume volume, String repGroupName) {
        BlockSnapshot snapshot = new BlockSnapshot();
        snapshot.setId(URIUtil.createId(BlockSnapshot.class));
        URI cgUri = volume.getConsistencyGroup();
        if (cgUri != null) {
            snapshot.setConsistencyGroup(cgUri);
        }
        snapshot.setSourceNativeId(volume.getNativeId());
        snapshot.setParent(new NamedURI(volume.getId(), volume.getLabel()));
        snapshot.setLabel(volume.getLabel() + "_" + repGroupName);
        snapshot.setStorageController(volume.getStorageController());
        snapshot.setVirtualArray(volume.getVirtualArray());
        snapshot.setProtocol(new StringSet());
        snapshot.getProtocol().addAll(volume.getProtocol());
        snapshot.setProject(new NamedURI(volume.getProject().getURI(), volume.getProject().getName()));
        snapshot.setSnapsetLabel(ResourceOnlyNameGenerator.removeSpecialCharsForName(
                volume.getLabel(), SmisConstants.MAX_SNAPSHOT_NAME_LENGTH));

        _dbClient.createObject(snapshot);

        return snapshot;
    }

    private Volume prepareClone(Volume volume, String repGroupName) {
        // create clone for the source
        Volume clone = new Volume();
        clone.setId(URIUtil.createId(Volume.class));
        clone.setLabel(volume.getLabel() + "_" + repGroupName);
        clone.setPool(volume.getPool());
        clone.setStorageController(volume.getStorageController());
        clone.setProject(new NamedURI(volume.getProject().getURI(), clone.getLabel()));
        clone.setTenant(new NamedURI(volume.getTenant().getURI(), clone.getLabel()));
        clone.setVirtualPool(volume.getVirtualPool());
        clone.setVirtualArray(volume.getVirtualArray());
        clone.setProtocol(new StringSet());
        clone.getProtocol().addAll(volume.getProtocol());
        clone.setThinlyProvisioned(volume.getThinlyProvisioned());
        clone.setOpStatus(new OpStatusMap());
        clone.setAssociatedSourceVolume(volume.getId());

        StringSet fullCopies = volume.getFullCopies();
        if (fullCopies == null) {
            fullCopies = new StringSet();
            volume.setFullCopies(fullCopies);
        }

        fullCopies.add(clone.getId().toString());

        _dbClient.createObject(clone);
        _dbClient.persistObject(volume);

        return clone;
    }

    /*
     * 1. for each newly created volumes in a CG, create a mirror
     * 2. add all mirrors to an existing replication group
     */
    private String createMirrorSteps(final Workflow workflow, String waitFor,
            final List<VolumeDescriptor> volumeDescriptors, List<Volume> volumeList, URI cgURI) {
        log.info("START create mirror steps");
        List<URI> sourceList = VolumeDescriptor.getVolumeURIs(volumeDescriptors);
        List<Volume> volumes = _dbClient.queryObject(Volume.class, sourceList);
        URI storage = volumes.get(0).getStorageController();
        StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, storage);

        Set<String> repGroupNames = ControllerUtils.getMirrorReplicationGroupNames(volumeList, _dbClient);
        if (repGroupNames.isEmpty()) {
            return waitFor;
        }

        for (String repGroupName : repGroupNames) {
            waitFor = addMirrorToReplicationGroupStep(workflow, waitFor, storageSystem, volumes, repGroupName, cgURI);
        }

        return waitFor;
    }

    private String addMirrorToReplicationGroupStep(final Workflow workflow, String waitFor, StorageSystem storageSystem,
            List<Volume> volumes, String repGroupName, URI cgURI) {
        log.info("START create mirror step");
        URI storage = storageSystem.getId();
        List<URI> mirrorList = new ArrayList<URI>();
        for (Volume volume : volumes) {
            String mirrorLabel = volume.getLabel() + "-" + repGroupName;
            BlockMirror mirror = createMirror(volume, volume.getVirtualPool(), volume.getPool(), mirrorLabel);
            URI mirrorId = mirror.getId();
            mirrorList.add(mirrorId);
            // TODO - use CreateListReplica to create mirrors of all volumes in one call
            waitFor = _blockDeviceController.addStepsForCreateMirrors(workflow, waitFor, storage,
                    volume.getId(), Arrays.asList(mirrorId), false);
        }
        // Wait for the complete StepGroup to complete.
        waitFor = BlockDeviceController.CREATE_MIRRORS_STEP_GROUP;
        waitFor = workflow.createStep(BlockDeviceController.UPDATE_CONSISTENCY_GROUP_STEP_GROUP,
                String.format("Updating consistency group  %s", cgURI), waitFor, storage,
                _blockDeviceController.getDeviceType(storage), this.getClass(),
                addToReplicationGroupMethod(storage, cgURI, repGroupName, mirrorList),
                _blockDeviceController.rollbackMethodNullMethod(), null);
        log.info(String.format("Step created for adding mirror [%s] to group on device [%s]",
                Joiner.on("\t").join(mirrorList), storage));

        return waitFor;
    }

    /**
     * Adds a BlockMirror structure for a Volume. It also calls addMirrorToVolume to
     * link the mirror into the volume's mirror set.
     * 
     * @param volume Volume
     * @param vPoolURI
     * @param recommendedPoolURI Pool that should be used to create the mirror
     * @param volumeLabel
     * @return BlockMirror (persisted)
     */
    private BlockMirror createMirror(Volume volume, URI vPoolURI, URI recommendedPoolURI, String volumeLabel) {
        BlockMirror createdMirror = new BlockMirror();
        createdMirror.setSource(new NamedURI(volume.getId(), volume.getLabel()));
        createdMirror.setId(URIUtil.createId(BlockMirror.class));
        URI cgUri = volume.getConsistencyGroup();
        if (!NullColumnValueGetter.isNullURI(cgUri)) {
            createdMirror.setConsistencyGroup(cgUri);
        }
        createdMirror.setLabel(volumeLabel);
        createdMirror.setStorageController(volume.getStorageController());
        createdMirror.setVirtualArray(volume.getVirtualArray());
        createdMirror.setProtocol(new StringSet());
        createdMirror.getProtocol().addAll(volume.getProtocol());
        createdMirror.setCapacity(volume.getCapacity());
        createdMirror.setProject(new NamedURI(volume.getProject().getURI(), createdMirror.getLabel()));
        createdMirror.setTenant(new NamedURI(volume.getTenant().getURI(), createdMirror.getLabel()));
        createdMirror.setPool(recommendedPoolURI);
        createdMirror.setVirtualPool(vPoolURI);
        createdMirror.setSyncState(BlockMirror.SynchronizationState.UNKNOWN.toString());
        createdMirror.setSyncType(BlockMirror.MIRROR_SYNC_TYPE);
        createdMirror.setThinlyProvisioned(volume.getThinlyProvisioned());
        _dbClient.createObject(createdMirror);
        addMirrorToVolume(volume, createdMirror);
        return createdMirror;
    }

    /**
     * Adds a Mirror structure to a Volume's mirror set.
     * 
     * @param volume
     * @param mirror
     */
    private void addMirrorToVolume(Volume volume, BlockMirror mirror) {
        StringSet mirrors = volume.getMirrors();
        if (mirrors == null) {
            mirrors = new StringSet();
        }
        mirrors.add(mirror.getId().toString());
        volume.setMirrors(mirrors);
        // Persist changes
        _dbClient.persistObject(volume);
    }

    public Workflow.Method addToReplicationGroupMethod(URI storage, URI consistencyGroup, String repGroupName,
            List<URI> addVolumesList) {
        return new Workflow.Method("addToReplicationGroup", storage, consistencyGroup, repGroupName, addVolumesList);
    }

    /**
     * Orchestration method for adding members to a replication group.
     *
     * @param storage
     * @param consistencyGroup
     * @param replicationGroupName
     * @param addVolumesList
     * @param opId
     * @return
     * @throws ControllerException
     */
    public boolean addToReplicationGroup(URI storage, URI consistencyGroup, String replicationGroupName, List<URI> addVolumesList,
            String opId)
            throws ControllerException {
        WorkflowStepCompleter.stepExecuting(opId);
        TaskCompleter taskCompleter = null;
        try {
            StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, storage);
            taskCompleter = new BlockConsistencyGroupUpdateCompleter(consistencyGroup, opId);
            _blockDeviceController.getDevice(storageSystem.getSystemType()).doAddToReplicationGroup(
                    storageSystem, consistencyGroup, replicationGroupName, addVolumesList, taskCompleter);
            WorkflowStepCompleter.stepSucceded(opId);
        } catch (Exception e) {
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            taskCompleter.error(_dbClient, serviceError);
            WorkflowStepCompleter.stepFailed(opId, serviceError);
            return false;
        }
        return true;
    }

    private boolean checkIfCGHasCloneReplica(List<Volume> volumes) {
        for (Volume volume : volumes) {
            StringSet fullCopies = volume.getFullCopies();
            if (fullCopies != null && !fullCopies.isEmpty()) {
                return true;
            }
        }

        return false;
    }

    private boolean checkIfCGHasMirrorReplica(List<Volume> volumes) {
        for (Volume volume : volumes) {
            StringSet mirrors = volume.getMirrors();
            if (mirrors != null && !mirrors.isEmpty()) {
                return true;
            }
        }

        return false;
    }

    private boolean checkIfCGHasSnapshotReplica(List<Volume> volumes) {
        for (Volume volume : volumes) {
            URIQueryResultList list = new URIQueryResultList();
            _dbClient.queryByConstraint(ContainmentConstraint.Factory.getVolumeSnapshotConstraint(volume.getId()),
                    list);
            Iterator<URI> it = list.iterator();
            while (it.hasNext()) {
                URI snapshotID = it.next();
                BlockSnapshot snapshot = _dbClient.queryObject(BlockSnapshot.class, snapshotID);
                if (snapshot != null) {
                    log.debug("There are Snapshot(s) available for volume {}", volume.getId());
                    return true;
                }
            }
        }

        return false;
    }

    @Override
    public String addStepsForDeleteVolumes(Workflow workflow, String waitFor, List<VolumeDescriptor> volumes,
            String taskId) throws InternalException {
        // Get the list of descriptors which represent source volumes to be deleted
        List<VolumeDescriptor> volumeDescriptors = VolumeDescriptor.filterByType(volumes,
                new VolumeDescriptor.Type[] { VolumeDescriptor.Type.BLOCK_DATA, VolumeDescriptor.Type.SRDF_SOURCE,
                        VolumeDescriptor.Type.SRDF_EXISTING_SOURCE,
                        VolumeDescriptor.Type.SRDF_TARGET }, null);

        // If no source volumes, just return
        if (volumeDescriptors.isEmpty()) {
            log.info("No replica steps required");
            return waitFor;
        }

        // Get the consistency group. If no consistency group for source
        // volumes,
        // just return. Get CG from any descriptor.
        final VolumeDescriptor firstVolumeDescriptor = volumeDescriptors.get(0);
        if (firstVolumeDescriptor != null) {
            Volume volume = _dbClient.queryObject(Volume.class, firstVolumeDescriptor.getVolumeURI());
            if (!(volume != null && volume.isInCG() && ControllerUtils.isVmaxVolumeUsing803SMIS(volume, _dbClient))) {
                return waitFor;
            }
        }

        // Get the consistency groups. If no consistency group for source
        // volumes,
        // just return. Get CGs from all descriptors.
        // Assume volumes could be in different CGs
        Map<URI, Set<URI>> cgToVolumes = new HashMap<URI, Set<URI>>();
        for (VolumeDescriptor volumeDescriptor : volumeDescriptors) {
            URI volumeURI = volumeDescriptor.getVolumeURI();
            Volume volume = _dbClient.queryObject(Volume.class, volumeURI);
            if (volume != null && volume.isInCG()) {
                URI cg = volume.getConsistencyGroup();
                Set<URI> cgVolumeList = cgToVolumes.get(cg);
                if (cgVolumeList == null) {
                    cgVolumeList = new HashSet<URI>();
                    cgToVolumes.put(cg, cgVolumeList);
                }

                cgVolumeList.add(volumeURI);
            }
        }

        if (cgToVolumes.isEmpty()) {
            return waitFor;
        }

        Set<Entry<URI, Set<URI>>> entrySet = cgToVolumes.entrySet();
        for (Entry<URI, Set<URI>> entry : entrySet) {
            // find member volumes in the group
            Set<URI> volumeURIs = entry.getValue();
            List<Volume> volumeList = _dbClient.queryObject(Volume.class, volumeURIs);
            boolean isRemoveAllFromCG = isRemoveAllFromCG(entry.getKey(), volumeList);
            log.info("isRemoveAllFromCG {}", isRemoveAllFromCG);
            if (checkIfCGHasCloneReplica(volumeList)) {
                log.info("Adding clone steps for detaching volumes");
                waitFor = detachCloneSteps(workflow, waitFor, volumeURIs, volumeList, isRemoveAllFromCG);
            }

            if (checkIfCGHasMirrorReplica(volumeList)) {
                log.info("Adding mirror steps for deleting volumes");
                // delete mirrors for the to be deleted volumes
                waitFor = deleteMirrorSteps(workflow, waitFor, volumeURIs, volumeList, isRemoveAllFromCG);
            }

            if (checkIfCGHasSnapshotReplica(volumeList)) {
                log.info("Adding snapshot steps for deleting volumes");
                // delete snapshots for the to be deleted volumes
                waitFor = deleteSnapshotSteps(workflow, waitFor, volumeURIs, volumeList, isRemoveAllFromCG);
            }
        }

        return waitFor;
    }

    private boolean isRemoveAllFromCG(URI cg, List<Volume> volumes) {
        URIQueryResultList cgVolumeList = new URIQueryResultList();
        _dbClient.queryByConstraint(ContainmentConstraint.Factory
                .getVolumesByConsistencyGroup(cg), cgVolumeList);
        int totalVolumeCount = 0;
        while (cgVolumeList.iterator().hasNext()) {
            Volume cgSourceVolume = _dbClient.queryObject(Volume.class, cgVolumeList.iterator().next());
            if (cgSourceVolume != null) {
                totalVolumeCount++;
            }
        }
        log.info("totalVolumeCount {} volume size {}", totalVolumeCount, volumes.size());
        return totalVolumeCount == volumes.size();
    }

    /**
     * Remove all snapshots from the volumes to be deleted.
     *
     * @param workflow
     * @param waitFor
     * @param volumeURIs
     * @param volumes
     * @return
     */
    private String deleteSnapshotSteps(Workflow workflow, String waitFor, Set<URI> volumeURIs, List<Volume> volumes, boolean isRemoveAll) {
        log.info("START delete snapshot steps");
        URI storage = volumes.get(0).getStorageController();
        StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, storage);

        Set<String> repGroupNames = ControllerUtils.getSnapshotReplicationGroupNames(volumes, _dbClient);
        if (repGroupNames.isEmpty()) {
            return waitFor;
        }

        for (String repGroupName : repGroupNames) {
            List<URI> snapList = getSnapshotsToBeRemoved(volumeURIs, repGroupName);
            if (!isRemoveAll) {
                URI cgURI = volumes.get(0).getConsistencyGroup();
                waitFor = removeSnapshotsFromReplicationGroupStep(workflow, waitFor, storageSystem, cgURI, snapList, repGroupName);
            }
            log.info("Adding delete snapshot steps");
            waitFor = _blockDeviceController.deleteSnapshotStep(workflow, waitFor, storage, storageSystem, snapList, isRemoveAll);
        }

        return waitFor;
    }

    /*
     * Detach all clones of the to be deleted volumes in a CG
     */
    private String
            detachCloneSteps(final Workflow workflow, String waitFor, Set<URI> volumeURIs, List<Volume> volumes, boolean isRemoveAll) {
        log.info("START detach clone steps");
        Set<String> repGroupNames = ControllerUtils.getCloneReplicationGroupNames(volumes, _dbClient);
        if (repGroupNames.isEmpty()) {
            return waitFor;
        }

        URI storage = volumes.get(0).getStorageController();
        StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, storage);
        for (String repGroupName : repGroupNames) {
            List<URI> cloneList = getClonesToBeRemoved(volumeURIs, repGroupName);
            if (!isRemoveAll) {
                URI cgURI = volumes.get(0).getConsistencyGroup();
                waitFor = removeClonesFromReplicationGroupStep(workflow, waitFor, storageSystem, cgURI, cloneList, repGroupName);
            }

            waitFor = _blockDeviceController.detachCloneStep(workflow, waitFor, storage, storageSystem, cloneList, isRemoveAll);
        }

        return waitFor;
    }

    /*
     * Delete all clones of the to be deleted volumes in a CG
     */
    private String deleteMirrorSteps(final Workflow workflow, String waitFor,
            Set<URI> volumeURIs, List<Volume> volumes, boolean isRemoveAll) {
        log.info("START delete mirror steps");
        Set<String> repGroupNames = ControllerUtils.getMirrorReplicationGroupNames(volumes, _dbClient);

        if (repGroupNames.isEmpty()) {
            return waitFor;
        }

        List<URI> mirrorList = new ArrayList<>();
        URI storage = volumes.get(0).getStorageController();
        StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, storage);
        for (String repGroupName : repGroupNames) {
            mirrorList = getMirrorsToBeRemoved(volumeURIs, repGroupName);
            if (!isRemoveAll) {
                URI cgURI = volumes.get(0).getConsistencyGroup();
                waitFor = removeMirrorsFromReplicationGroupStep(workflow, waitFor, storageSystem, cgURI, mirrorList, repGroupName);
            }

            waitFor = _blockDeviceController.deleteMirrorStep(workflow, waitFor, storage, storageSystem, mirrorList, isRemoveAll);
        }

        return waitFor;
    }

    private String removeSnapshotsFromReplicationGroupStep(final Workflow workflow, String waitFor,
            StorageSystem storageSystem,
            URI cgURI, List<URI> snapshots, String repGroupName) {
        log.info("START remove snapshot from CG steps");
        URI storage = storageSystem.getId();
        waitFor = workflow.createStep(BlockDeviceController.UPDATE_CONSISTENCY_GROUP_STEP_GROUP,
                String.format("Updating consistency group  %s", cgURI), waitFor, storage,
                _blockDeviceController.getDeviceType(storage), this.getClass(),
                removeFromReplicationGroupMethod(storage, cgURI, repGroupName, snapshots),
                _blockDeviceController.rollbackMethodNullMethod(), null);
        log.info(String.format("Step created for remove snapshots [%s] to group on device [%s]",
                Joiner.on("\t").join(snapshots), storage));

        return waitFor;
    }

    private String removeClonesFromReplicationGroupStep(final Workflow workflow, String waitFor, StorageSystem storageSystem,
            URI cgURI, List<URI> cloneList, String repGroupName) {
        log.info("START remove clone from CG steps");
        URI storage = storageSystem.getId();
        waitFor = workflow.createStep(BlockDeviceController.UPDATE_CONSISTENCY_GROUP_STEP_GROUP,
                String.format("Updating consistency group  %s", cgURI), waitFor, storage,
                _blockDeviceController.getDeviceType(storage), this.getClass(),
                removeFromReplicationGroupMethod(storage, cgURI, repGroupName, cloneList),
                _blockDeviceController.rollbackMethodNullMethod(), null);
        log.info(String.format("Step created for remove clones [%s] from group on device [%s]",
                Joiner.on("\t").join(cloneList), storage));

        return waitFor;
    }

    private String removeMirrorsFromReplicationGroupStep(final Workflow workflow, String waitFor, StorageSystem storageSystem,
            URI cgURI, List<URI> mirrorList, String repGroupName) {
        log.info("START remove mirror from CG steps");
        URI storage = storageSystem.getId();
        waitFor = workflow.createStep(BlockDeviceController.UPDATE_CONSISTENCY_GROUP_STEP_GROUP,
                String.format("Updating consistency group  %s", cgURI), waitFor, storage,
                _blockDeviceController.getDeviceType(storage), this.getClass(),
                removeFromReplicationGroupMethod(storage, cgURI, repGroupName, mirrorList),
                _blockDeviceController.rollbackMethodNullMethod(), null);
        log.info(String.format("Step created for remove mirrors [%s] from group on device [%s]",
                Joiner.on("\t").join(mirrorList), storage));

        return waitFor;
    }

    private List<URI> getSnapshotsToBeRemoved(Set<URI> volumes, String repGroupName) {
        List<URI> replicas = new ArrayList<>();
        URIQueryResultList queryResults = new URIQueryResultList();
        _dbClient.queryByConstraint(AlternateIdConstraint.Factory
                .getSnapshotReplicationGroupInstanceConstraint(repGroupName), queryResults);
        Iterator<URI> resultsIter = queryResults.iterator();
        while (resultsIter.hasNext()) {
            BlockSnapshot snapshot = _dbClient.queryObject(BlockSnapshot.class, resultsIter.next());
            if (volumes.contains(snapshot.getParent().getURI())) {
                replicas.add(snapshot.getId());
            }
        }

        return replicas;
    }

    private List<URI> getClonesToBeRemoved(Set<URI> volumes, String repGroupName) {
        List<URI> replicas = new ArrayList<URI>();
        URIQueryResultList queryResults = new URIQueryResultList();
        _dbClient.queryByConstraint(AlternateIdConstraint.Factory
                .getCloneReplicationGroupInstanceConstraint(repGroupName), queryResults);
        Iterator<URI> resultsIter = queryResults.iterator();
        while (resultsIter.hasNext()) {
            Volume clone = _dbClient.queryObject(Volume.class, resultsIter.next());
            if (volumes.contains(clone.getAssociatedSourceVolume())) {
                replicas.add(clone.getId());
            }
        }

        return replicas;
    }

    private List<URI> getMirrorsToBeRemoved(Set<URI> volumes, String repGroupName) {
        List<URI> replicas = new ArrayList<URI>();
        URIQueryResultList queryResults = new URIQueryResultList();
        _dbClient.queryByConstraint(AlternateIdConstraint.Factory
                .getMirrorReplicationGroupInstanceConstraint(repGroupName), queryResults);
        Iterator<URI> resultsIter = queryResults.iterator();
        while (resultsIter.hasNext()) {
            BlockMirror mirror = _dbClient.queryObject(BlockMirror.class, resultsIter.next());
            if (volumes.contains(mirror.getSource().getURI())) {
                replicas.add(mirror.getId());
            }
        }

        return replicas;
    }

    static Workflow.Method removeFromReplicationGroupMethod(URI storage, URI consistencyGroup, String repGroupName,
            List<URI> addVolumesList) {
        return new Workflow.Method("removeFromReplicationGroup", storage, consistencyGroup, repGroupName, addVolumesList);
    }

    /**
     * Orchestration method for removing members from a replication group.
     *
     * @param storage
     * @param consistencyGroup
     * @param repGroupName
     * @param addVolumesList
     * @param opId
     * @return
     * @throws ControllerException
     */
    public boolean removeFromReplicationGroup(URI storage, URI consistencyGroup, String repGroupName, List<URI> addVolumesList,
            String opId)
            throws ControllerException {
        TaskCompleter taskCompleter = null;
        try {
            StorageSystem storageSystem = _dbClient.queryObject(StorageSystem.class, storage);
            taskCompleter = new BlockConsistencyGroupUpdateCompleter(consistencyGroup, opId);
            _blockDeviceController.getDevice(storageSystem.getSystemType()).doRemoveFromReplicationGroup(
                    storageSystem, consistencyGroup, repGroupName, addVolumesList, taskCompleter);
        } catch (Exception e) {
            ServiceError serviceError = DeviceControllerException.errors.jobFailed(e);
            taskCompleter.error(_dbClient, serviceError);
            WorkflowStepCompleter.stepFailed(opId, serviceError);
            return false;
        }
        return true;
    }

    @Override
    public String addStepsForPostDeleteVolumes(Workflow workflow, String waitFor, List<VolumeDescriptor> volumes,
            String taskId, VolumeWorkflowCompleter completer) {
        // Nothing to do, no steps to add
        return waitFor;
    }

    @Override
    public String addStepsForExpandVolume(Workflow workflow, String waitFor, List<VolumeDescriptor> volumeDescriptors,
            String taskId) throws InternalException {
        // Nothing to do, no steps to add
        return waitFor;
    }

    @Override
    public String addStepsForChangeVirtualPool(Workflow workflow, String waitFor, List<VolumeDescriptor> volumes,
            String taskId) throws InternalException {
        // Nothing to do, no steps to add
        return waitFor;
    }

    @Override
    public String addStepsForChangeVirtualArray(Workflow workflow, String waitFor, List<VolumeDescriptor> volumes,
            String taskId) throws InternalException {
        // Nothing to do, no steps to add
        return waitFor;
    }

    @Override
    public String addStepsForRestoreVolume(Workflow workflow, String waitFor, URI storage, URI pool, URI volume, URI snapshot,
            Boolean updateOpStatus, String taskId, BlockSnapshotRestoreCompleter completer) throws InternalException {
        // Nothing to do, no steps to add
        return waitFor;
    }

    public void rollbackMethodNull(String stepId) throws WorkflowException {
        WorkflowStepCompleter.stepSucceded(stepId);
    }
}
