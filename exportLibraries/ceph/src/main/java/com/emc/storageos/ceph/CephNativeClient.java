package com.emc.storageos.ceph;

import java.util.ArrayList;
import java.util.List;

import com.google.common.collect.Lists;

import com.ceph.rados.IoCTX;
import com.ceph.rados.Rados;
import com.ceph.rados.exceptions.RadosException;
import com.ceph.rbd.jna.RbdSnapInfo;
import com.ceph.rbd.Rbd;
import com.ceph.rbd.RbdException;
import com.ceph.rbd.RbdImage;

import com.emc.storageos.ceph.model.ClusterInfo;
import com.emc.storageos.ceph.model.PoolInfo;
import com.emc.storageos.ceph.model.SnapInfo;

public class CephNativeClient implements CephClient {

	private static final long LAYERING = 1;
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
            long features = LAYERING;
            rbd.create(name, size, features);
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

    @Override
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

    @Override
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

    @Override
    public void cloneSnap(String pool, String parentImage, String parentSnap, String childName) throws CephException {
        IoCTX ioCtx = null;
        try {
            ioCtx = _rados.ioCtxCreate(pool);
            Rbd rbd = new Rbd(ioCtx);
            long features = LAYERING;
            int order = 0;
            rbd.clone(parentImage, parentSnap, ioCtx, childName, features, order);
        } catch (RadosException | RbdException e) {
            throw CephException.exceptions.operationException(e);
        } finally {
            if (ioCtx != null)
                _rados.ioCtxDestroy(ioCtx);
        }
    }

    @Override
    public void protectSnap(String pool, String parentImage, String snapName) throws CephException {
        IoCTX ioCtx = null;
        try {
            ioCtx = _rados.ioCtxCreate(pool);
            Rbd rbd = new Rbd(ioCtx);
            RbdImage image = rbd.open(parentImage);
            try {
                image.snapProtect(snapName);
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

    @Override
    public void unprotectSnap(String pool, String parentImage, String snapName) throws CephException {
        IoCTX ioCtx = null;
        try {
            ioCtx = _rados.ioCtxCreate(pool);
            Rbd rbd = new Rbd(ioCtx);
            RbdImage image = rbd.open(parentImage);
            try {
                image.snapUnprotect(snapName);
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

    @Override
    public boolean snapIsProtected(String pool, String parentImage, String snapName) throws CephException {
        boolean result = false;
        IoCTX ioCtx = null;
        try {
            ioCtx = _rados.ioCtxCreate(pool);
            Rbd rbd = new Rbd(ioCtx);
            RbdImage image = rbd.open(parentImage);
            try {
            	result = image.snapIsProtected(snapName);
            } finally {
                rbd.close(image);
            }
        } catch (RadosException | RbdException e) {
            throw CephException.exceptions.operationException(e);
        } finally {
            if (ioCtx != null)
                _rados.ioCtxDestroy(ioCtx);
        }
        return result;
    }

    @Override
    public void flattenImage(String pool, String imageName) throws CephException {
        IoCTX ioCtx = null;
        try {
            ioCtx = _rados.ioCtxCreate(pool);
            Rbd rbd = new Rbd(ioCtx);
            RbdImage image = rbd.open(imageName);
            try {
                image.flatten();
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
    
    @Override
    public List<SnapInfo> getSnapshots(String pool, String imageName) throws CephException {
    	List<SnapInfo> result = new ArrayList<SnapInfo>();
    	IoCTX ioCtx = null;
        try {
            ioCtx = _rados.ioCtxCreate(pool);
            Rbd rbd = new Rbd(ioCtx);
            RbdImage image = rbd.open(imageName);
            try {
                List<RbdSnapInfo> snapList = image.snapList();
                for (RbdSnapInfo snap: snapList) {
                	SnapInfo snapInfo = new SnapInfo();
                	snapInfo.setId(snap.id);
                	snapInfo.setName(snap.name);
                	result.add(snapInfo);
                }
            } finally {
                rbd.close(image);
            }
        } catch (RadosException | RbdException e) {
            throw CephException.exceptions.operationException(e);
        } finally {
            if (ioCtx != null)
                _rados.ioCtxDestroy(ioCtx);
        }
        return result;
    }

    @Override
    public List<String> getChildren(String pool, String parentImage, String snapName) throws CephException {
    	List<String> result = new ArrayList<String>();
    	IoCTX ioCtx = null;
        try {
            ioCtx = _rados.ioCtxCreate(pool);
            Rbd rbd = new Rbd(ioCtx);
            RbdImage image = rbd.open(parentImage);
            try {
            	result.addAll(image.listChildren(snapName));
            } finally {
                rbd.close(image);
            }
        } catch (RadosException | RbdException e) {
            throw CephException.exceptions.operationException(e);
        } finally {
            if (ioCtx != null)
                _rados.ioCtxDestroy(ioCtx);
        }
        return result;
    }
}
