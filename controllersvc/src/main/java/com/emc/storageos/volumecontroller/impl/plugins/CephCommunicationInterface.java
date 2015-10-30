package com.emc.storageos.volumecontroller.impl.plugins;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.ceph.api.CephClientFactory;
import com.emc.storageos.plugins.AccessProfile;
import com.emc.storageos.plugins.BaseCollectionException;

public class CephCommunicationInterface extends ExtendedCommunicationInterfaceImpl {
    private static final Logger _log = LoggerFactory.getLogger(CephCommunicationInterface.class);

    public void setCephClientFactory(CephClientFactory cephClientFactory) {
    }

    @Override
    public void collectStatisticsInformation(AccessProfile accessProfile) throws BaseCollectionException {
        _log.info("Starting collecting statistics of Ceph StorageProvider. IP={}", accessProfile.getIpAddress());
    }

    @Override
    public void scan(AccessProfile accessProfile) throws BaseCollectionException {
        _log.info("Starting scan of Ceph StorageProvider. IP={}", accessProfile.getIpAddress());
    }

    @Override
    public void discover(AccessProfile accessProfile) throws BaseCollectionException {
        _log.info("Starting discovery of Ceph StorageProvider. IP={}", accessProfile.getIpAddress());
    }

}
