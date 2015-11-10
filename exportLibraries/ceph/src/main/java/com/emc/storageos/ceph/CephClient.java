package com.emc.storageos.ceph;

import java.util.List;

import com.emc.storageos.ceph.model.ClusterInfo;
import com.emc.storageos.ceph.model.PoolInfo;

public interface CephClient {

    public ClusterInfo getClusterInfo() throws CephException;
    public List<PoolInfo> getPools() throws CephException;
}
