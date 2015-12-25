package com.emc.storageos.ceph.model;

public class ClusterInfo {

    private String _fsid;
    private long _kb;
    private long _kbAvail;

    public String getFsid() {
        return _fsid;
    }

    public void setFsid(final String fsid) {
        _fsid = fsid;
    }

    public long getKb() {
        return _kb;
    }

    public void setKb(long kb) {
        _kb = kb;
    }

    public long getKbAvail() {
        return _kbAvail;
    }

    public void setKbAvail(long kbAvail) {
        _kbAvail = kbAvail;
    }
}
