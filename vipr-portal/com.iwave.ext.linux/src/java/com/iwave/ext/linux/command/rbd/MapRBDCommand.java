/*
 * Copyright (c) 2012-2015 EMC Software LLC
 * All Rights Reserved
 */
package com.iwave.ext.linux.command.rbd;

import com.iwave.ext.linux.command.LinuxCommand;


public class MapRBDCommand extends LinuxCommand {

    public MapRBDCommand() {
        setCommand("rbd");
        addArgument("map");
        setRunAsRoot(true);
    }
    
    public void setVolume(String pool, String name) {
    	addArgument(String.format("%s/%s", pool, name));
    }
}
