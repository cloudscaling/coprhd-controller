/*
 * Copyright (c) 2012-2015 EMC Software LLC
 * All Rights Reserved
 */
package com.iwave.ext.linux.command.rbd;


import com.iwave.ext.linux.command.LinuxResultsCommand;


public class MapRBDCommand extends LinuxResultsCommand<String> {
	private String _monitors;
	private String _user;
	private String _key;
	private String _template;
	
	public MapRBDCommand(String monitors, String user, String key) {
		this._monitors = monitors;
		this._user = user;
		this._key = key;
		StringBuilder sb = new StringBuilder();
		sb.append("volumes=( $(ls /sys/bus/rbd/devices/ | sort) );");
		sb.append("size=${#volumes[@]};");
		sb.append("echo '%s name=%s,secret=%s %s %s %s' > /sys/bus/rbd/add;");
		sb.append("if [[ $? != 0 ]]; then");
		sb.append("  exit -1;");
		sb.append("fi;");    			
		sb.append("new_volumes=( $(ls /sys/bus/rbd/devices/ | sort) );");
		sb.append("new_size=${#new_volumes[@]};");
		sb.append("id=-1;");
		sb.append("index=0;");
		sb.append("while [  $index -lt $new_size ]; do");
		sb.append("  v1=${new_volumes[$index]};");
		sb.append("  v2=${volumes[$index]};");
		sb.append("  if [[ $v1 != $v2 ]]; then");
		sb.append("    p=$(cat /sys/bus/rbd/devices/$v1/pool);");
		sb.append("    n=$(cat /sys/bus/rbd/devices/$v1/name);");
		sb.append("    s=$(cat /sys/bus/rbd/devices/$v1/current_snap);");		
		sb.append("    if [[ $p == %s && $n == %s && $s == %s ]]; then");
		sb.append("      id=$index;");
		sb.append("      break;");
		sb.append("    fi;");
		sb.append("  fi;");
		sb.append("  let index=$index+1;");
		sb.append("done;");
		sb.append("if [ $id -eq  -1 ]; then");
		sb.append("  exit -1;");
		sb.append("fi;");
		sb.append("echo $id;");
		this._template = sb.toString();
        setRunAsRoot(true);
    }
    
    public void setVolume(String pool, String volume, String snapshot) {
    	if (snapshot == null || snapshot.isEmpty())
    		snapshot = "-";
    	String cmd = String.format(_template, _monitors, _user, _key, pool, volume, snapshot, pool, volume, snapshot);
    	setCommand(cmd);
    }

    @Override
    public void parseOutput() {
        String stdout = getOutput().getStdout();
        results = stdout.trim();
    }
}
