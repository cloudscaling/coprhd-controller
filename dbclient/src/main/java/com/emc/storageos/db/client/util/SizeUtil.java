/*
 * Copyright (c) 2008-2011 EMC Corporation
 * All Rights Reserved
 */

package com.emc.storageos.db.client.util;

/**
 * @author burckb
 * 
 */
public class SizeUtil {

    public static final String SIZE_B = "B";
    public static final String SIZE_MB = "MB";
    public static final String SIZE_GB = "GB";
    public static final String SIZE_TB = "TB";
    public static final String SIZE_KB = "KB";

    public static Long translateSize(String size) {
        long sizeVal = 0;
        long multiplier = 1;
        String sizeSubstr;
        if (size.endsWith(SIZE_TB)) {
            multiplier = 1024 * 1024 * 1024 * 1024L;
            sizeSubstr = size.substring(0, size.length() - 2);
        } else if (size.endsWith(SIZE_GB)) {
            multiplier = 1024 * 1024 * 1024L;
            sizeSubstr = size.substring(0, size.length() - 2);
        } else if (size.endsWith(SIZE_MB)) {
            multiplier = 1024 * 1024L;
            sizeSubstr = size.substring(0, size.length() - 2);
        } else if (size.endsWith(SIZE_B)) {
            sizeSubstr = size.substring(0, size.length() - 1);
        } else {
            sizeSubstr = size;
        }
        Double d = Double.valueOf(sizeSubstr.trim()) * multiplier;
        sizeVal = d.longValue();
        return Long.valueOf(sizeVal);
    }

    /**
     * Given size in bytes, return converted value as TB, GB, MB as specified in "to"
     * 
     * @param size size in byes
     * @param to convert to
     * @return converted size
     */
    public static Long translateSize(Long size, String to) {
        Long multiplier = 1L;
        if (to.endsWith(SIZE_TB)) {
            multiplier = 1024 * 1024 * 1024 * 1024L;
        } else if (to.endsWith(SIZE_GB)) {
            multiplier = 1024 * 1024 * 1024L;
        } else if (to.endsWith(SIZE_MB)) {
            multiplier = 1024 * 1024L;
        } else if (to.endsWith(SIZE_KB)) {
        	multiplier = 1024L;
        }
        Double d = Double.valueOf(size / (double) multiplier);
        long sizeVal = d.longValue();
        return Long.valueOf(sizeVal);
    }
}
