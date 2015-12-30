/*
 * Copyright (c) 2012-2015 EMC Software LLC
 * All Rights Reserved
 */
package com.iwave.ext.linux.command.rbd;


import com.iwave.ext.linux.command.LinuxCommand;


public class UnmapRBDCommand extends LinuxCommand {
	private String _template;
	
    public UnmapRBDCommand() {
        setRunAsRoot(true);
        
		StringBuilder sb = new StringBuilder();
		sb.append("pool=%s;");
		sb.append("vol=%s;");
		sb.append("snap=%s;");
		// remove key and record in rdbmap (remove mappnig on reboot)
		sb.append("RBDMAP_FILE=\"/etc/ceph/rbdmap\";");
		sb.append("if [ -f \"$RBDMAP_FILE\" ]; then");
		sb.append("  grep -v \"$pool/$vol@$snap\" \"$RBDMAP_FILE\" > \"$RBDMAP_FILE.tmp\";");
		sb.append("  mv -f \"$RBDMAP_FILE.tmp\" \"$RBDMAP_FILE\";");
		sb.append("  keyfile=\"/etc/ceph/$pool.$vol.$snap.keyfile\";");
		sb.append("  key=$(grep -o \"$keyfile\" \"$RBDMAP_FILE\");");
		sb.append("  if [ -z \"$key\" ]; then");
		sb.append("    rm -f \"$keyfile\";");
		sb.append("  fi;");
		sb.append("fi;");

		// exit if already unmounted
		sb.append("if [[ \"$snap\" == \"-\" ]]; then");
		sb.append("  vol=$(readlink \"/dev/rbd/$pool/$vol\");"); // pool/vol
		sb.append("else");
		sb.append("  vol=$(readlink \"/dev/rbd/$pool/$vol@$snap\");"); // pool/vol@snap
		sb.append("fi;");		
		sb.append("id=$(echo \"$vol\" | grep -o '[0-9]*');");
		sb.append("if [ -z \"$id\" ]; then");
		//	find by name
		sb.append("  volumes=( $(ls /sys/bus/rbd/devices/ | sort) );");
		sb.append("  size=${#volumes[@]};");
		sb.append("  index=0;");
		sb.append("  while [  $index -lt $size ]; do");
		sb.append("    v=${volumes[$index]};");
		sb.append("    p=$(cat /sys/bus/rbd/devices/$v/pool);");
		sb.append("    n=$(cat /sys/bus/rbd/devices/$v/name);");
		sb.append("    s=$(cat /sys/bus/rbd/devices/$v/current_snap);");		
		sb.append("    if [[ \"$p\" == \"$pool\" && \"$n\" == \"$vol\" && \"$s\" == \"$snap\" ]]; then");
		sb.append("      id=$index;");
		sb.append("      break;");
		sb.append("    fi;");
		sb.append("    let index=$index+1;");		
		sb.append("  done;");		
		sb.append("fi;");		
		
		sb.append("if [ -z \"$id\" ]; then");
		sb.append("    exit -1;");
		sb.append("fi;");		
		
		// do unmap
		sb.append("echo \"$id\" > /sys/bus/rbd/remove || exit -1;");
		this._template = sb.toString();
    }
    
    public void setVolume(String pool, String volume, String snapshot) {
    	if (snapshot == null || snapshot.isEmpty())
    		snapshot = "-";
    	String cmd = String.format(_template, pool, volume, snapshot);
    	setCommand(cmd);
    }
}
