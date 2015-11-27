package com.emc.storageos.ceph;

import java.util.List;

import com.emc.storageos.ceph.model.ClusterInfo;
import com.emc.storageos.ceph.model.PoolInfo;

public interface CephClient {

    public ClusterInfo getClusterInfo() throws CephException;
    public List<PoolInfo> getPools() throws CephException;
    public void createImage(String pool, String name, long size) throws CephException;
    public void deleteImage(String pool, String name) throws CephException;
    public void resizeImage(String pool, String name, long size) throws CephException;
}
