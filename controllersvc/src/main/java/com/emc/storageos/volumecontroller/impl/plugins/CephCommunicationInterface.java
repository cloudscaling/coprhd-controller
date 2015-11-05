package com.emc.storageos.volumecontroller.impl.plugins;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.ceph.CephClient;
import com.emc.storageos.ceph.CephClientFactory;
import com.emc.storageos.ceph.model.ClusterInfo;
import com.emc.storageos.db.client.model.StorageProvider;
import com.emc.storageos.plugins.AccessProfile;
import com.emc.storageos.plugins.BaseCollectionException;

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
        try {
            CephClient cephClient = _cephClientFactory.getClient(monitorHost, userName, userKey);
            ClusterInfo clusterInfo = cephClient.getClusterInfo();
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
