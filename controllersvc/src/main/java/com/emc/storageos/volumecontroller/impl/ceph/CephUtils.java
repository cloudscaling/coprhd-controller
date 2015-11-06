package com.emc.storageos.volumecontroller.impl.ceph;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.ceph.CephClientFactory;
import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.model.StorageProvider;
import com.emc.storageos.db.client.model.StorageProvider.ConnectionStatus;

public class CephUtils {

    private static final Logger _log = LoggerFactory.getLogger(CephUtils.class);

    public static List<URI> refreshCephConnections(final List<StorageProvider> cephProviderList,
            DbClient dbClient) {
        List<URI> activeProviders = new ArrayList<URI>();
        for (StorageProvider storageProvider : cephProviderList) {
            try {
                CephClientFactory factory = new CephClientFactory();
                factory.getClient(storageProvider.getIPAddress(),
                        storageProvider.getUserName(), storageProvider.getPassword());
                storageProvider.setConnectionStatus(ConnectionStatus.CONNECTED.name());
                activeProviders.add(storageProvider.getId());
            } catch (Exception e) {
                _log.error(String.format("Failed to connect to Ceph %s: %s",
                        storageProvider.getIPAddress(), storageProvider.getId()), e);
                storageProvider.setConnectionStatus(ConnectionStatus.NOTCONNECTED.name());
            } finally {
                dbClient.updateObject(storageProvider);
            }
        }
        return activeProviders;
    }
}