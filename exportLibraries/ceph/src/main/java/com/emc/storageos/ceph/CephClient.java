package com.emc.storageos.ceph;

import com.emc.storageos.ceph.model.ClusterInfo;

public interface CephClient {

    public ClusterInfo getClusterInfo() throws CephException;
}
