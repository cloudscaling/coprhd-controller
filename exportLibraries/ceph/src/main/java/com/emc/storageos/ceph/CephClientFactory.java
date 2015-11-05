package com.emc.storageos.ceph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CephClientFactory {
    private Logger _log = LoggerFactory.getLogger(CephClientFactory.class);

    public void init() {
        _log.info("CephClient factory initialized");
    }

    public CephClient getClient(final String monitorHost, final String userName, final String userKey) throws CephException {
        return new CephNativeClient(monitorHost, userName, userKey);
    }
}
