package com.emc.storageos.volumecontroller.impl.plugins;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.ceph.CephClient;
import com.emc.storageos.ceph.CephClientFactory;
import com.emc.storageos.ceph.model.ClusterInfo;
import com.emc.storageos.db.client.model.StorageProvider;
import com.emc.storageos.db.client.model.StorageSystem;
import com.emc.storageos.plugins.AccessProfile;
import com.emc.storageos.plugins.BaseCollectionException;
import com.emc.storageos.plugins.StorageSystemViewObject;
import com.emc.storageos.volumecontroller.impl.NativeGUIDGenerator;

public class CephCommunicationInterface extends ExtendedCommunicationInterfaceImpl {
    private static final Logger _log = LoggerFactory.getLogger(CephCommunicationInterface.class);

    private CephClientFactory _cephClientFactory;

    public void setCephClientFactory(CephClientFactory cephClientFactory) {
        _cephClientFactory = cephClientFactory;
    }

    @Override
    public void collectStatisticsInformation(AccessProfile accessProfile) throws BaseCollectionException {
        _log.info("Starting collecting statistics of Ceph StorageProvider. IP={}", accessProfile.getIpAddress());
    }

    @Override
    public void scan(AccessProfile accessProfile) throws BaseCollectionException {
        _log.info("Starting scan of Ceph StorageProvider. IP={}", accessProfile.getIpAddress());
        StorageProvider provider = _dbClient.queryObject(StorageProvider.class, accessProfile.getSystemId());
        String monitorHost = provider.getIPAddress();
        String userName = provider.getUserName();
        String userKey = provider.getPassword();
        StorageProvider.ConnectionStatus status = StorageProvider.ConnectionStatus.NOTCONNECTED;
        Map<String, StorageSystemViewObject> storageSystemsCache = accessProfile.getCache();
        String cephType = StorageSystem.Type.ceph.name();
        try {
            CephClient cephClient = _cephClientFactory.getClient(monitorHost, userName, userKey);
            ClusterInfo clusterInfo = cephClient.getClusterInfo();
            String systemNativeGUID = NativeGUIDGenerator.generateNativeGuid(cephType, clusterInfo.getFsid());
            StorageSystemViewObject viewObject = storageSystemsCache.get(systemNativeGUID);
            if (viewObject == null) {
                viewObject = new StorageSystemViewObject();
            }
            viewObject.setDeviceType(cephType);
            viewObject.addprovider(accessProfile.getSystemId().toString());
            viewObject.setProperty(StorageSystemViewObject.SERIAL_NUMBER, clusterInfo.getFsid());
            viewObject.setProperty(StorageSystemViewObject.STORAGE_NAME, systemNativeGUID);
            viewObject.setProperty(StorageSystemViewObject.MODEL, "Ceph RBD");
//            storageSystemsCache.put(systemNativeGUID, viewObject);
            status = StorageProvider.ConnectionStatus.CONNECTED;
        } finally {
            provider.setConnectionStatus(status.name());
            _dbClient.updateObject(provider);
        }
    }

    @Override
    public void discover(AccessProfile accessProfile) throws BaseCollectionException {
        _log.info("Starting discovery of Ceph StorageProvider. IP={}", accessProfile.getIpAddress());
    }
}
