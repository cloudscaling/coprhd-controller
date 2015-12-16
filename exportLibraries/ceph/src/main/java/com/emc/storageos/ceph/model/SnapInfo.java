package com.emc.storageos.ceph.model;

public class SnapInfo {

    private long _id;
    private String _name;

    public long getId() {
        return _id;
    }

    public void setId(long id) {
        _id = id;
    }

    public String getName() {
        return _name;
    }

    public void setName(final String name) {
        _name = name;
    }
}
