package com.emc.storageos.ceph;

import com.ceph.rados.IoCTX;
import com.ceph.rbd.RbdException;
import com.ceph.rbd.RbdImage;

public class Rbd extends com.ceph.rbd.Rbd {
    final static com.ceph.rbd.jna.Rbd rbd;

    static {
        rbd = com.ceph.rbd.jna.Rbd.INSTANCE;
    }

    public Rbd(IoCTX io) {
        super(io);
    }

    public void resize(RbdImage image, long size) throws RbdException {
        int r = rbd.rbd_resize(image.getPointer(), size);
        if (r < 0) {
            throw new RbdException("Failed to resize image", r);
        }
    }
}
