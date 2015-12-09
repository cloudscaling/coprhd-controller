/*
 * Copyright (c) 2012-2015 EMC Software LLC
 * All Rights Reserved
 */
package com.iwave.ext.linux.command.rbd;


import com.iwave.ext.linux.command.LinuxCommand;


public class MapRBDCommand extends LinuxCommand {

	private static final String CMD_TEMPLATE = "echo '%s name=%s,secret=%s %s %s' > /sys/bus/rbd/add";

	
    public MapRBDCommand() {
    	setRunAsRoot(true);
    }
    
    public void setVolume(String monitors, String user, String key, String pool, String volume) {
    	setCommand(String.format(CMD_TEMPLATE, monitors,user, key, pool, volume));
    }

//    @Override
//    public void parseOutput() {
//        String stdout = getOutput().getStdout();
//        results = stdout.trim();
//    }
}
