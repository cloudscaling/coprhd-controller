package com.emc.storageos.ceph.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CephClientFactory {
    private Logger _log = LoggerFactory.getLogger(CephClientFactory.class);

    public void init() {
        _log.info("CephClient factory initialized");
    }
}
