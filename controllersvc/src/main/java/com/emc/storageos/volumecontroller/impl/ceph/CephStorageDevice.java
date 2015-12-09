package com.emc.storageos.volumecontroller.impl.ceph;

import java.net.URI;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.ceph.CephClient;
import com.emc.storageos.ceph.CephClientFactory;
import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.model.BlockSnapshot;
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
import com.emc.storageos.volumecontroller.DefaultBlockStorageDevice;
import com.emc.storageos.volumecontroller.SnapshotOperations;
import com.emc.storageos.volumecontroller.TaskCompleter;
import com.emc.storageos.volumecontroller.impl.ControllerUtils;
import com.emc.storageos.volumecontroller.impl.NativeGUIDGenerator;
import com.emc.storageos.volumecontroller.impl.smis.SmisConstants;
import com.emc.storageos.volumecontroller.impl.utils.VirtualPoolCapabilityValuesWrapper;
import com.google.common.collect.Lists;

public class CephStorageDevice extends DefaultBlockStorageDevice {

    private static final Logger _log = LoggerFactory.getLogger(CephStorageDevice.class);

    private DbClient _dbClient;
    private CephClientFactory _cephClientFactory;
    private SnapshotOperations snapshotOperations;
    private NameGenerator _nameGenerator;

    public void setDbClient(DbClient dbClient) {
        _dbClient = dbClient;
    }

    public void setCephClientFactory(CephClientFactory cephClientFactory) {
        _cephClientFactory = cephClientFactory;
    }

    public void setSnapshotOperations(SnapshotOperations snapshotOperations) {
        this.snapshotOperations = snapshotOperations;
    }

    public void setNameGenerator(NameGenerator nameGenerator) {
        _nameGenerator = nameGenerator;
    }

    @Override
    public void doCreateVolumes(StorageSystem storage, StoragePool storagePool, String opId, List<Volume> volumes,
            VirtualPoolCapabilityValuesWrapper capabilities, TaskCompleter taskCompleter) throws DeviceControllerException {
        String monitorHost = storage.getSmisProviderIP();
        String userName = storage.getSmisUserName();
        String userKey = storage.getSmisPassword();
        try {
            CephClient cephClient = _cephClientFactory.getClient(monitorHost, userName, userKey);
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
                volume.setWWN(label);
                volume.setNativeGuid(NativeGUIDGenerator.generateNativeGuid(_dbClient, volume));
                volume.setDeviceLabel(volume.getLabel());
                volume.setProvisionedCapacity(volume.getCapacity());
                volume.setAllocatedCapacity(volume.getCapacity());
                // volume.setInactive(false); why ???
            }
            _dbClient.updateObject(volumes);
            taskCompleter.ready(_dbClient);
        } catch (Exception e) {
            _log.error("Error while creating volumes", e);
            for (Volume volume : volumes) {
                volume.setInactive(true);
            }
            _dbClient.updateObject(volumes);
            ServiceError error = DeviceControllerErrors.ceph.operationFailed("doCreateVolumes", e.getMessage());
            taskCompleter.error(_dbClient, error);
        }
    }

    @Override
    public void doDeleteVolumes(StorageSystem storage, String opId, List<Volume> volumes, TaskCompleter taskCompleter)
            throws DeviceControllerException {
        URI poolUri = volumes.get(0).getPool();
        String monitorHost = storage.getSmisProviderIP();
        String userName = storage.getSmisUserName();
        String userKey = storage.getSmisPassword();
        try {
            StoragePool pool = _dbClient.queryObject(StoragePool.class, poolUri);
            CephClient cephClient = _cephClientFactory.getClient(monitorHost, userName, userKey);
            for (Volume volume : volumes) {
                cephClient.deleteImage(pool.getPoolName(), volume.getNativeId());
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
        String monitorHost = storage.getSmisProviderIP();
        String userName = storage.getSmisUserName();
        String userKey = storage.getSmisPassword();
        try {
            CephClient cephClient = _cephClientFactory.getClient(monitorHost, userName, userKey);
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
    public void doCreateSnapshot(StorageSystem storage, List<URI> snapshotList, Boolean createInactive, Boolean readOnly,
            TaskCompleter taskCompleter) throws DeviceControllerException {
        Iterator<BlockSnapshot> snapshots = _dbClient.queryIterativeObjects(BlockSnapshot.class, snapshotList);
        List<BlockSnapshot> blockSnapshots = Lists.newArrayList(snapshots);
        if (ControllerUtils.checkSnapshotsInConsistencyGroup(blockSnapshots, _dbClient, taskCompleter)) {
            super.doCreateSnapshot(storage, snapshotList, createInactive, readOnly, taskCompleter);
        } else {
            URI snapshot = blockSnapshots.get(0).getId();
            snapshotOperations.createSingleVolumeSnapshot(storage, snapshot, createInactive,
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
            snapshotOperations.deleteSingleVolumeSnapshot(storage, snapshot, taskCompleter);
        }
    }

}
