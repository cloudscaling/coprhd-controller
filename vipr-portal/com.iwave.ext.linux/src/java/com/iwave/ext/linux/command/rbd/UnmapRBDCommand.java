/*
 * Copyright (c) 2012-2015 EMC Software LLC
 * All Rights Reserved
 */
package com.iwave.ext.linux.command.rbd;


import com.iwave.ext.linux.command.LinuxCommand;


public class UnmapRBDCommand extends LinuxCommand {
	private static final String CMD_TEMPLATE = "echo '%s' > /sys/bus/rbd/remove";
	
    public UnmapRBDCommand() {
        setRunAsRoot(true);
    }
    
    public void setVolume(Integer volume) {
        setCommand(String.format(CMD_TEMPLATE, volume));
    }    
}
