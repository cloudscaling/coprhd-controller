package com.emc.storageos.ceph;

import java.util.ArrayList;
import java.util.List;

import com.ceph.rados.IoCTX;
import com.ceph.rados.Rados;
import com.ceph.rados.exceptions.RadosException;
import com.ceph.rbd.Rbd;
import com.ceph.rbd.RbdException;
import com.ceph.rbd.RbdImage;
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

    @Override
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

    @Override
    public void createImage(String pool, String name, long size) throws CephException {
        IoCTX ioCtx = null;
        try {
            ioCtx = _rados.ioCtxCreate(pool);
            Rbd rbd = new Rbd(ioCtx);
            rbd.create(name, size, 0L);
        } catch (RadosException | RbdException e) {
            throw CephException.exceptions.operationException(e);
        } finally {
            if (ioCtx != null)
                _rados.ioCtxDestroy(ioCtx);
        }
    }

    @Override
    public void deleteImage(String pool, String name) throws CephException {
        IoCTX ioCtx = null;
        try {
            ioCtx = _rados.ioCtxCreate(pool);
            Rbd rbd = new Rbd(ioCtx);
            rbd.remove(name);
        } catch (RadosException | RbdException e) {
            throw CephException.exceptions.operationException(e);
        } finally {
            if (ioCtx != null)
                _rados.ioCtxDestroy(ioCtx);
        }
    }

    @Override
    public void resizeImage(String pool, String name, long size) throws CephException {
        IoCTX ioCtx = null;
        try {
            ioCtx = _rados.ioCtxCreate(pool);
            Rbd rbd = new Rbd(ioCtx);
            RbdImage image = rbd.open(name);
            try {
                image.resize(size);
            } finally {
                rbd.close(image);
            }
        } catch (RadosException | RbdException e) {
            throw CephException.exceptions.operationException(e);
        } finally {
            if (ioCtx != null)
                _rados.ioCtxDestroy(ioCtx);
        }
    }

    public void createSnap(String pool, String imageName, String snapName) throws CephException {
        IoCTX ioCtx = null;
        try {
            ioCtx = _rados.ioCtxCreate(pool);
            Rbd rbd = new Rbd(ioCtx);
            RbdImage image = rbd.open(imageName);
            try {
                image.snapCreate(snapName);
            } finally {
                rbd.close(image);
            }
        } catch (RadosException | RbdException e) {
            throw CephException.exceptions.operationException(e);
        } finally {
            if (ioCtx != null)
                _rados.ioCtxDestroy(ioCtx);
        }
    }

    public void deleteSnap(String pool, String imageName, String snapName) throws CephException {
        IoCTX ioCtx = null;
        try {
            ioCtx = _rados.ioCtxCreate(pool);
            Rbd rbd = new Rbd(ioCtx);
            RbdImage image = rbd.open(imageName);
            try {
                image.snapRemove(snapName);
            } finally {
                rbd.close(image);
            }
        } catch (RadosException | RbdException e) {
            throw CephException.exceptions.operationException(e);
        } finally {
            if (ioCtx != null)
                _rados.ioCtxDestroy(ioCtx);
        }
    }
}
