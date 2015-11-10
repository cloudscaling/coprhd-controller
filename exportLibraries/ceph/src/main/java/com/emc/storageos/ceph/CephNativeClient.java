package com.emc.storageos.ceph;

import java.util.ArrayList;
import java.util.List;

import com.ceph.rados.Rados;
import com.ceph.rados.exceptions.RadosException;
import com.emc.storageos.ceph.model.ClusterInfo;
import com.emc.storageos.ceph.model.PoolInfo;

public class CephNativeClient implements CephClient {

    private Rados _rados;

    public CephNativeClient(final String monitorHost, final String userName, final String userKey) throws CephException {
        _rados = new Rados(userName);
        try {
            _rados.confSet("mon_host", monitorHost);
            _rados.confSet("key", userKey);
            _rados.connect();
        } catch (RadosException e) {
            throw CephException.exceptions.connectionError(e);
        }
    }

    @Override
    public ClusterInfo getClusterInfo() throws CephException {
        ClusterInfo info = new ClusterInfo();
        try {
            info.setFsid(_rados.clusterFsid());
        } catch (RadosException e) {
            throw CephException.exceptions.operationException(e);
        }
        return info;
    }

    public List<PoolInfo> getPools() throws CephException {
        List<PoolInfo> pools = new ArrayList<PoolInfo>();
        try {
            String[] poolNames = _rados.poolList();
            for (String poolName: poolNames) {
                PoolInfo poolInfo = new PoolInfo();
                poolInfo.setName(poolName);
                poolInfo.setId(_rados.poolLookup(poolName));
                pools.add(poolInfo);
            }
        } catch (RadosException e) {
            throw CephException.exceptions.operationException(e);
        }
        return pools;
    }
}
