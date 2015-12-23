package com.emc.storageos.volumecontroller.impl.ceph;

import static java.util.Arrays.asList;

import java.net.URI;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.ceph.CephClient;
import com.emc.storageos.ceph.CephClientFactory;
import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.URIUtil;
import com.emc.storageos.db.client.model.BlockConsistencyGroup;
import com.emc.storageos.db.client.model.BlockMirror;
import com.emc.storageos.db.client.model.BlockObject;
import com.emc.storageos.db.client.model.BlockSnapshot;
import com.emc.storageos.db.client.model.ExportGroup;
import com.emc.storageos.db.client.model.ExportMask;
import com.emc.storageos.db.client.model.Host;
import com.emc.storageos.db.client.model.HostInterface;
import com.emc.storageos.db.client.model.Initiator;
import com.emc.storageos.db.client.model.StoragePool;
import com.emc.storageos.db.client.model.StorageSystem;
import com.emc.storageos.db.client.model.TenantOrg;
import com.emc.storageos.db.client.model.Volume;
import com.emc.storageos.db.client.util.NameGenerator;
import com.emc.storageos.db.exceptions.DatabaseException;
import com.emc.storageos.exceptions.DeviceControllerErrors;
import com.emc.storageos.exceptions.DeviceControllerException;
import com.emc.storageos.svcs.errorhandling.model.ServiceCoded;
import com.emc.storageos.svcs.errorhandling.model.ServiceError;
import com.emc.storageos.volumecontroller.CloneOperations;
import com.emc.storageos.volumecontroller.DefaultBlockStorageDevice;
import com.emc.storageos.volumecontroller.SnapshotOperations;
import com.emc.storageos.volumecontroller.TaskCompleter;
import com.emc.storageos.volumecontroller.impl.ControllerUtils;
import com.emc.storageos.volumecontroller.impl.NativeGUIDGenerator;
import com.emc.storageos.volumecontroller.impl.smis.SmisConstants;
import com.emc.storageos.volumecontroller.impl.utils.ExportMaskUtils;
import com.emc.storageos.volumecontroller.impl.utils.VirtualPoolCapabilityValuesWrapper;
import com.iwave.ext.linux.LinuxSystemCLI;

import com.google.common.collect.Lists;


public class CephStorageDevice extends DefaultBlockStorageDevice {

    private static final Logger _log = LoggerFactory.getLogger(CephStorageDevice.class);

    private DbClient _dbClient;
    private CephClientFactory _cephClientFactory;
    private SnapshotOperations _snapshotOperations;
    private CloneOperations _cloneOperations;
    private NameGenerator _nameGenerator;

    public void setDbClient(DbClient dbClient) {
        _dbClient = dbClient;
    }

    public void setCephClientFactory(CephClientFactory cephClientFactory) {
        _cephClientFactory = cephClientFactory;
    }

    public void setSnapshotOperations(SnapshotOperations snapshotOperations) {
        this._snapshotOperations = snapshotOperations;
    }

    public void setCloneOperations(CloneOperations cloneOperations) {
        this._cloneOperations = cloneOperations;
    }

    public void setNameGenerator(NameGenerator nameGenerator) {
        _nameGenerator = nameGenerator;
    }

    @Override
    public boolean validateStorageProviderConnection(String ipAddress, Integer portNumber) {
        return false;
    }

    @Override
    public void doConnect(StorageSystem storage) {
        _log.info("doConnect {} (nothing to do for ceph)", storage.getId().toString());
    }

    @Override
    public void doDisconnect(StorageSystem storage) {
        _log.info("doDisconnect {} (nothing to do for ceph)", storage.getId().toString());
    }

    @Override
    public String doAddStorageSystem(StorageSystem storage) throws DeviceControllerException {
        _log.info("doAddStorageSystem {} (nothing to do for ceph, just return null)", storage.getId().toString());
        return null;
    }

    @Override
    public void doRemoveStorageSystem(StorageSystem storage) throws DeviceControllerException {
        _log.info("doRemoveStorageSystem {} (nothing to do for ceph)", storage.getId().toString());
    }
    
    @Override
    public void doCreateVolumes(StorageSystem storage, StoragePool storagePool, String opId, List<Volume> volumes,
            VirtualPoolCapabilityValuesWrapper capabilities, TaskCompleter taskCompleter) throws DeviceControllerException {
        try {
            CephClient cephClient = getClient(storage);
            for (Volume volume : volumes) {
                String tenantName = "";
                try
                {
                    TenantOrg tenant = _dbClient.queryObject(TenantOrg.class, volume.getTenant().getURI());
                    tenantName = tenant.getLabel();
                } catch (DatabaseException e)
                {
                    _log.error("Error lookup TenantOrg object", e);
                }
                String label = _nameGenerator.generate(tenantName, volume.getLabel(), volume.getId().toString(),
                        '-', SmisConstants.MAX_VOLUME_NAME_LENGTH);
                cephClient.createImage(storagePool.getPoolName(), label, volume.getCapacity()); // / 1073741824L);
                volume.setNativeId(label);
                volume.setNativeGuid(NativeGUIDGenerator.generateNativeGuid(_dbClient, volume));
                volume.setDeviceLabel(volume.getLabel());
                volume.setProvisionedCapacity(volume.getCapacity());
                volume.setAllocatedCapacity(volume.getCapacity());
                volume.setInactive(false);
            }
            _dbClient.updateObject(volumes);
            taskCompleter.ready(_dbClient);
        } catch (Exception e) {
            _log.error("Error while creating volumes", e);
            _dbClient.updateObject(volumes);
            ServiceError error = DeviceControllerErrors.ceph.operationFailed("doCreateVolumes", e.getMessage());
            taskCompleter.error(_dbClient, error);
        }
    }

    @Override
    public void doDeleteVolumes(StorageSystem storage, String opId, List<Volume> volumes, TaskCompleter taskCompleter)
            throws DeviceControllerException {
        URI poolUri = volumes.get(0).getPool();
        try {
            StoragePool pool = _dbClient.queryObject(StoragePool.class, poolUri);
            CephClient cephClient = getClient(storage);
            for (Volume volume : volumes) {
            	if (volume.getNativeId() != null && !volume.getNativeId().isEmpty()) {
                	cephClient.deleteImage(pool.getPoolName(), volume.getNativeId());
            	} else {
                    _log.info("Volume {} was not created completely, so skip real deletion and just delete it from DB", volume.getLabel());            		
            	}
                volume.setInactive(true);
                _dbClient.updateObject(volume);
            }
            taskCompleter.ready(_dbClient);
        } catch (Exception e) {
            _log.error("Error while deleting volumes", e);
            ServiceCoded code = DeviceControllerErrors.ceph.operationFailed("deleteVolume", e.getMessage());
            taskCompleter.error(_dbClient, code);
        }
    }

    @Override
    public void doExpandVolume(StorageSystem storage, StoragePool pool, Volume volume, Long size, TaskCompleter taskCompleter)
            throws DeviceControllerException {
        try {
            CephClient cephClient = getClient(storage);
            cephClient.resizeImage(pool.getPoolName(), volume.getNativeId(), size);
            volume.setProvisionedCapacity(size);
            volume.setAllocatedCapacity(size);
            volume.setCapacity(size);
            _dbClient.updateObject(volume);
            taskCompleter.ready(_dbClient);
        } catch (Exception e) {
            _log.error("Error while expanding volumes", e);
            ServiceCoded code = DeviceControllerErrors.ceph.operationFailed("expandVolume", e.getMessage());
            taskCompleter.error(_dbClient, code);
        }
    }
    
    @Override
    public void doExportGroupCreate(StorageSystem storage, ExportMask exportMask,
            Map<URI, Integer> volumeMap, List<Initiator> initiators, List<URI> targets,
            TaskCompleter taskCompleter) throws DeviceControllerException {
        _log.info("{} Ceph doExportGroupCreate START ...", storage.getSerialNumber());
		filterInitiators(initiators);    		
		mapVolumes(storage, exportMask, volumeMap, initiators, taskCompleter);    	
        _log.info("{} doExportGroupCreate END...", storage.getSerialNumber()); 	
    }
    
    @Override
    public void doExportGroupDelete(StorageSystem storage, ExportMask exportMask, TaskCompleter taskCompleter)
            throws DeviceControllerException {
        _log.info("{} Ceph: doExportGroupDelete START ...", storage.getSerialNumber());
    	List<URI> volumeURIs = ExportMaskUtils.getVolumeURIs(exportMask);
        Set<Initiator> initiators = ExportMaskUtils.getInitiatorsForExportMask(_dbClient, exportMask, null);
        filterInitiators(initiators);
        unmapVolumes(storage, exportMask, volumeURIs, initiators, taskCompleter);
        _log.info("{} Ceph: doExportGroupDelete END...", storage.getSerialNumber()); 	
    }
    
    @Override
    public void doExportAddVolumes(StorageSystem storage, ExportMask exportMask, Map<URI, Integer> volumeMap, TaskCompleter taskCompleter)
            throws DeviceControllerException {
        _log.info("{} Ceph: doExportAddVolumes START ...", storage.getSerialNumber());
        Set<Initiator> initiators = ExportMaskUtils.getInitiatorsForExportMask(_dbClient, exportMask, null);
        filterInitiators(initiators);
		mapVolumes(storage, exportMask, volumeMap, initiators, taskCompleter);    	
        _log.info("{}  Ceph: doExportAddVolumes END...", storage.getSerialNumber()); 	

    }

    @Override
    public void doExportRemoveVolumes(StorageSystem storage, ExportMask exportMask, List<URI> volumeURIs, TaskCompleter taskCompleter)
            throws DeviceControllerException {
        _log.info("{} Ceph doExportRemoveVolumes START ...", storage.getSerialNumber());
        Set<Initiator> initiators = ExportMaskUtils.getInitiatorsForExportMask(_dbClient, exportMask, null);
        filterInitiators(initiators);
        unmapVolumes(storage, exportMask, volumeURIs, initiators, taskCompleter);
        _log.info("{} Ceph: doExportRemoveVolumes END...", storage.getSerialNumber()); 	
    }

    @Override
    public void doExportAddVolume(StorageSystem storage, ExportMask exportMask, URI volume, Integer lun, TaskCompleter taskCompleter)
            throws DeviceControllerException {
        Map<URI, Integer> volumes = new HashMap<>();
        volumes.put(volume, lun);
        doExportAddVolumes(storage, exportMask, volumes, taskCompleter);
    }

    @Override
    public void doExportRemoveVolume(StorageSystem storage, ExportMask exportMask, URI volume, TaskCompleter taskCompleter)
            throws DeviceControllerException {
        doExportRemoveVolumes(storage, exportMask, asList(volume), taskCompleter);
    }

    @Override
    public void doExportAddInitiators(StorageSystem storage, ExportMask exportMask, List<Initiator> initiators, List<URI> targets,
            TaskCompleter taskCompleter) throws DeviceControllerException {
        _log.info("{} Ceph doExportAddInitiators START ...", storage.getSerialNumber());
        Map<URI, Integer> volumes = createVolumeMapForExportMask(exportMask);
        mapVolumes(storage, exportMask, volumes, initiators, taskCompleter);
        _log.info("{} Ceph: doExportAddInitiators END...", storage.getSerialNumber()); 	
    }

    @Override
    public void doExportRemoveInitiators(StorageSystem storage, ExportMask exportMask, List<Initiator> initiators, List<URI> targets,
            TaskCompleter taskCompleter) throws DeviceControllerException {
        _log.info("{} Ceph doExportRemoveInitiators START ...", storage.getSerialNumber());
        List<URI> volumeURIs = ExportMaskUtils.getVolumeURIs(exportMask);
        unmapVolumes(storage, exportMask, volumeURIs, initiators, taskCompleter);
        _log.info("{} Ceph: doExportRemoveInitiators END...", storage.getSerialNumber()); 	
    }

    @Override
    public void doExportAddInitiator(StorageSystem storage, ExportMask exportMask, Initiator initiator, List<URI> targets,
            TaskCompleter taskCompleter) throws DeviceControllerException {
        doExportAddInitiators(storage, exportMask, asList(initiator), targets,  taskCompleter);
    }

    @Override
    public void doExportRemoveInitiator(StorageSystem storage, ExportMask exportMask, Initiator initiator, List<URI> targets,
            TaskCompleter taskCompleter) throws DeviceControllerException {
        doExportRemoveInitiators(storage, exportMask, asList(initiator), targets, taskCompleter);
    }
    
    @Override
    public void doCreateSnapshot(StorageSystem storage, List<URI> snapshotList, Boolean createInactive, Boolean readOnly,
            TaskCompleter taskCompleter) throws DeviceControllerException {
        Iterator<BlockSnapshot> snapshots = _dbClient.queryIterativeObjects(BlockSnapshot.class, snapshotList);
        List<BlockSnapshot> blockSnapshots = Lists.newArrayList(snapshots);
        if (ControllerUtils.checkSnapshotsInConsistencyGroup(blockSnapshots, _dbClient, taskCompleter)) {
            super.doCreateSnapshot(storage, snapshotList, createInactive, readOnly, taskCompleter);
        } else {
            URI snapshot = blockSnapshots.get(0).getId();
            _snapshotOperations.createSingleVolumeSnapshot(storage, snapshot, createInactive,
                    readOnly, taskCompleter);
        }
    }

    @Override
    public void doDeleteSnapshot(StorageSystem storage, URI snapshot, TaskCompleter taskCompleter) throws DeviceControllerException {
        Iterator<BlockSnapshot> snapshots = _dbClient.queryIterativeObjects(BlockSnapshot.class, Arrays.asList(snapshot));
        List<BlockSnapshot> blockSnapshots = Lists.newArrayList(snapshots);
        if (ControllerUtils.checkSnapshotsInConsistencyGroup(blockSnapshots, _dbClient, taskCompleter)) {
            super.doDeleteSnapshot(storage, snapshot, taskCompleter);
        } else {
            _snapshotOperations.deleteSingleVolumeSnapshot(storage, snapshot, taskCompleter);
        }
    }

    @Override
    public void doCreateClone(StorageSystem storage, URI sourceVolume, URI cloneVolume, Boolean createInactive,
            TaskCompleter taskCompleter) {    	
        if (ControllerUtils.checkCloneConsistencyGroup(cloneVolume, _dbClient, taskCompleter)) {
            completeTaskAsUnsupported(taskCompleter);
        } else {
            _cloneOperations.createSingleClone(storage, sourceVolume, cloneVolume, createInactive, taskCompleter);
        }
    }

    @Override
    public void doCreateConsistencyGroup(StorageSystem storage, URI consistencyGroup, TaskCompleter taskCompleter)
            throws DeviceControllerException {
        _log.error("Consistency groups are not supported for Ceph cluster");
        completeTaskAsUnsupported(taskCompleter);
    }

    @Override
    public void doDeleteConsistencyGroup(StorageSystem storage, URI consistencyGroup, Boolean markInactive, TaskCompleter taskCompleter)
            throws DeviceControllerException {
        _log.error("Consistency groups are not supported for Ceph cluster");
        completeTaskAsUnsupported(taskCompleter);
    }    

    @Override
    public void doDetachClone(StorageSystem storage, URI cloneVolume, TaskCompleter taskCompleter) {
        if (ControllerUtils.checkCloneConsistencyGroup(cloneVolume, _dbClient, taskCompleter)) {
            completeTaskAsUnsupported(taskCompleter);
        } else {
            _cloneOperations.detachSingleClone(storage, cloneVolume, taskCompleter);
        }
    }
    
    @Override
    public void doFractureClone(StorageSystem storageDevice, URI source, URI clone,
            TaskCompleter taskCompleter) {
        completeTaskAsUnsupported(taskCompleter);
    }

    @Override
    public void doRestoreFromClone(StorageSystem storage, URI cloneVolume,
            TaskCompleter taskCompleter) {
        completeTaskAsUnsupported(taskCompleter);
    }

    @Override
    public void doResyncClone(StorageSystem storage, URI cloneVolume,
            TaskCompleter taskCompleter) {
        completeTaskAsUnsupported(taskCompleter);
    }

    @Override
    public void doCreateGroupClone(StorageSystem storageDevice, List<URI> clones,
            Boolean createInactive, TaskCompleter taskCompleter) {
        completeTaskAsUnsupported(taskCompleter);
    }

    @Override
    public void doDetachGroupClone(StorageSystem storage, List<URI> cloneVolume,
            TaskCompleter taskCompleter) {
        completeTaskAsUnsupported(taskCompleter);
    }

    @Override
    public void doRestoreFromGroupClone(StorageSystem storageSystem, List<URI> cloneVolume,
            TaskCompleter taskCompleter) {
        completeTaskAsUnsupported(taskCompleter);

    }

    @Override
    public void doActivateGroupFullCopy(StorageSystem storageSystem,
            List<URI> fullCopy, TaskCompleter completer) {
        completeTaskAsUnsupported(completer);

    }

    @Override
    public void doResyncGroupClone(StorageSystem storageDevice,
            List<URI> clone, TaskCompleter completer) throws Exception {
        completeTaskAsUnsupported(completer);
    }

    @Override
    public Integer checkSyncProgress(URI storage, URI source, URI target) {
        return null;
    }

    @Override
    public void doWaitForSynchronized(Class<? extends BlockObject> clazz, StorageSystem storageObj, URI target, TaskCompleter completer) {
        _log.info("Nothing to do here.  Ceph does not require a wait for synchronization");
        completer.ready(_dbClient);
    }

    @Override
    public void doWaitForGroupSynchronized(StorageSystem storageObj, List<URI> target, TaskCompleter completer)
    {
        _log.info("Nothing to do here.  Ceph does not require a wait for synchronization");
        completer.ready(_dbClient);
    }
    
    /**
     * Method calls the completer with error message indicating that the caller's method is unsupported
     * 
     * @param completer [in] - TaskCompleter
     */
    private void completeTaskAsUnsupported(TaskCompleter completer) {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        String methodName = stackTrace[2].getMethodName();
        ServiceCoded code = DeviceControllerErrors.ceph.operationIsUnsupported(methodName);
        completer.error(_dbClient, code);
    }

    private CephClient getClient(StorageSystem storage) {
        return CephUtils.connectToCeph(_cephClientFactory, storage);
    }
    
    private LinuxSystemCLI getLinuxClient(Host host) {
        LinuxSystemCLI cli = new LinuxSystemCLI();
        cli.setHost(host.getHostName());
        cli.setPort(host.getPortNumber());
        cli.setUsername(host.getUsername());
        cli.setPassword(host.getPassword());
        cli.setHostId(host.getId());
        return cli;
    }
    
    private void filterInitiators(Collection<Initiator> initiators) {
        Iterator<Initiator> initiatorIterator = initiators.iterator();
        while (initiatorIterator.hasNext()) {
            Initiator initiator = initiatorIterator.next();
            if (!initiator.getProtocol().equals(Initiator.Protocol.RBD.name())) {
                initiatorIterator.remove();
            }
        }
    }

    private Map<URI, Integer> createVolumeMapForExportMask(ExportMask exportMask) {
        Map<URI, Integer> map = new HashMap<>();
        for (URI uri : ExportMaskUtils.getVolumeURIs(exportMask)) {
            map.put(uri, ExportGroup.LUN_UNASSIGNED);
        }
        return map;
    }

    private void mapVolumes(StorageSystem storage, ExportMask exportMask, Map<URI, Integer> volumeMap, Collection<Initiator> initiators,
    		TaskCompleter completer) {
        _log.info("mapVolumes: exportMask id: {}", exportMask.getId());
        _log.info("mapVolumes: volumeMap: {}", volumeMap);    	
        _log.info("mapVolumes: initiators: {}", initiators);
    	try {
	        for (Map.Entry<URI, Integer> volMapEntry : volumeMap.entrySet()) {
	        	URI objectUri = volMapEntry.getKey();
	        	BlockObject object = Volume.fetchExportMaskBlockObject(_dbClient, objectUri);
	        	Volume volume = null;
	        	BlockSnapshot snapshot = null;
	        	if (URIUtil.isType(objectUri, Volume.class) || URIUtil.isType(objectUri, BlockMirror.class)) {
		        	volume = (Volume)object;
	        	} else if (URIUtil.isType(objectUri, BlockSnapshot.class)) {
	        		snapshot = (BlockSnapshot)object;
	        		volume = _dbClient.queryObject(Volume.class, snapshot.getParent());
	        	} else {
                	String msg = String.format("Unsupported block object type URI %s", objectUri);
                    ServiceCoded code = DeviceControllerErrors.ceph.operationFailed("mapVolumes", msg);
                    completer.error(_dbClient, code);
                    return;
	        	}
	            StoragePool pool = _dbClient.queryObject(StoragePool.class, volume.getPool());
	            String poolName = pool.getPoolName();
	            String volumeName = volume.getNativeId();
	            String snapshotName = null;
	            if (snapshot != null) {
	            	snapshotName = snapshot.getNativeId();
	            }	            
	            String monitorAddress = storage.getSmisProviderIP();
	            String monitorUser = storage.getSmisUserName();
	            String monitorKey = storage.getKeyringKey();
	            for (Initiator initiator : initiators) {
	            	Host host = _dbClient.queryObject(Host.class, initiator.getHost());
	                if (initiator.getProtocol().equals(HostInterface.Protocol.RBD.name())) {
	                	_log.info(String.format("mapVolume: host %s pool %s volume %s", host.getHostName(), poolName, volumeName));    	
	                	LinuxSystemCLI linuxClient = getLinuxClient(host);
	                	String id = linuxClient.mapRBD(monitorAddress, monitorUser, monitorKey, poolName, volumeName, snapshotName);
	                	exportMask.addVolume(object.getId(), Integer.valueOf(id));
	                	_dbClient.updateAndReindexObject(exportMask);
	                } else {
	                	String msg = String.format("Unexpected initiator protocol %s, port %s, pool %s, volume %s",
	                			initiator.getProtocol(), initiator.getInitiatorPort(), poolName, volumeName);
	                    ServiceCoded code = DeviceControllerErrors.ceph.operationFailed("mapVolumes", msg);
	                    completer.error(_dbClient, code);
	                    return;
	                }
	            }
	        }
	        completer.ready(_dbClient);
        } catch (Exception e) {
            _log.error("Encountered an exception", e);
            ServiceCoded code = DeviceControllerErrors.ceph.operationFailed("mapVolumes", e.getMessage());
            completer.error(_dbClient, code);
        }
    }

    private void unmapVolumes(StorageSystem storage, ExportMask exportMask, List<URI> volumeURIs, Collection<Initiator> initiators,
            TaskCompleter completer) {
        _log.info("unmapVolumes: volumeURIs: {}", volumeURIs);
        _log.info("unmapVolumes: initiators: {}", initiators);
    	try {
	    	for (URI volumeURI : volumeURIs) {
	    		if (exportMask.checkIfVolumeHLUSet(volumeURI)) {
	            	_log.warn("Attempted to unmap BlockObject {}, which has not HLU set", volumeURI.toString());
	            	continue;
	    		}
	    		Integer volumeNumber = Integer.valueOf(exportMask.returnVolumeHLU(volumeURI));
	    		BlockObject blockObject = BlockObject.fetch(_dbClient, volumeURI);
	            if (blockObject == null) {
	            	_log.warn("Attempted to unmap BlockObject {}, which is empty", volumeURI.toString());
	            	continue;
	    		}
	            String nativeId = blockObject.getNativeId();
	            if (blockObject.getInactive()) {
	            	_log.warn("Attempted to unmap BlockObject {} ({}), which is inactive", nativeId, volumeURI.toString());
	                continue;
	            }
	            String device = blockObject.getDeviceLabel();
	        	if (device.isEmpty()) {
	        		_log.warn("Attend to unmap BlockObject {} with empty device path", nativeId);
	        		continue;
	        	}
	            for (Initiator initiator : initiators) {
	            	Host host = _dbClient.queryObject(Host.class, initiator.getHost());            	
	                String port = initiator.getInitiatorPort();
	                if (initiator.getProtocol().equals(HostInterface.Protocol.RBD.name())) {
	            		LinuxSystemCLI linuxClient = getLinuxClient(host);
	            		linuxClient.unmapRBD(volumeNumber);
	            		exportMask.removeVolume(volumeURI);
	                	_dbClient.updateAndReindexObject(exportMask);
	                } else {
	                	String msgPattern = "Unexpected initiator protocol %s for port %s and nativeId %s";
	                	String msg = String.format(msgPattern, initiator.getProtocol(), port, nativeId);
	                	ServiceCoded code = DeviceControllerErrors.ceph.operationFailed("unmapVolumes", msg);
	                    completer.error(_dbClient, code);
	                    return;
	                }
	            }
	        }
	        completer.ready(_dbClient);
        } catch (Exception e) {
            _log.error("Encountered an exception", e);
            ServiceCoded code = DeviceControllerErrors.ceph.operationFailed("unmapVolumes", e.getMessage());
            completer.error(_dbClient, code);
        }
    }

}
