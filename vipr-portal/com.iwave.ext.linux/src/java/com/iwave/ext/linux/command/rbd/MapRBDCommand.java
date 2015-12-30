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
		// dev path definitions		
		sb.append("if [[ \"%6$s\" == \"-\" ]]; then");
		sb.append("  vol_path=\"/dev/rbd/%4$s/%5$s\";"); // pool/vol
		sb.append("else");
		sb.append("  vol_path=\"/dev/rbd/%4$s/%5$s@%6$s\";"); // pool/vol@snap
		sb.append("fi;");

		// exit if already mounted
		sb.append("if [ -f \"$vol_path\" ]; then");
		sb.append("  exit -1;");
		sb.append("fi;");

		// current state
		sb.append("volumes=( $(ls /sys/bus/rbd/devices/ | sort) );");
		sb.append("size=${#volumes[@]};");
		
		// do mapping
		sb.append("  echo \"%1$s name=%2$s,secret=%3$s %4$s %5$s %6$s\" > \"/sys/bus/rbd/add\" || exit -1;");

		// detect volume id (number)
		sb.append("for i in {0..2}; do");
		sb.append("  vol=$(readlink \"$vol_path\");");
		sb.append("  if [ ! -z \"$vol\" ]; then break; fi;");
		sb.append("  sleep 1;");
		sb.append("done;");
		sb.append("id=$(echo \"$vol\" | grep -o '[0-9]*');");
		sb.append("if [ -z \"$id\" ]; then");
		// old driver version
		sb.append("  new_volumes=( $(ls /sys/bus/rbd/devices/ | sort) );");
		sb.append("  new_size=${#new_volumes[@]};");
		sb.append("  index=0;");
		sb.append("  while [  $index -lt $new_size ]; do");
		sb.append("    v1=${new_volumes[$index]};");
		sb.append("    v2=${volumes[$index]};");
		sb.append("    if [[ $v1 != $v2 ]]; then");
		sb.append("      p=$(cat /sys/bus/rbd/devices/$v1/pool);");
		sb.append("      n=$(cat /sys/bus/rbd/devices/$v1/name);");
		sb.append("      s=$(cat /sys/bus/rbd/devices/$v1/current_snap);");		
		sb.append("      if [[ $p == \"%4$s\" && $n == \"%4$s\" && $s == \"%$6s\" ]]; then");
		sb.append("        id=$index;");
		sb.append("        break;");
		sb.append("      fi;");
		sb.append("    fi;");
		sb.append("    let index=$index+1;");
		sb.append("  done;");		
		sb.append("fi;");

		sb.append("if [ -z \"$id\" ]; then");
		sb.append("  exit -1;");
		sb.append("fi;");		
		
		// if ceph common installed (rbdmap service scripts) add info for persistent mapping (on reboot)
		sb.append("RBDMAP_FILE=\"/etc/ceph/rbdmap\";");
		sb.append("if [ -f \"$RBDMAP_FILE\" ]; then");
		sb.append("  keyfile=\"/etc/ceph/%4$s.%5$s.%6$s.keyfile\";");
		sb.append("  echo \"%3$s\" > \"$keyfile\";");
		sb.append("  yes | cp -f \"$RBDMAP_FILE\" \"$RBDMAP_FILE.org\";");
		sb.append("  yes | cp -f \"$RBDMAP_FILE\" \"$RBDMAP_FILE.new\";");
		sb.append("  echo \"%4$s/%5$s@%6$s        id=%2$s,keyfile=$keyfile\" >> \"$RBDMAP_FILE.new\";");		
		sb.append("  mv -f \"$RBDMAP_FILE.new\" \"$RBDMAP_FILE\";");
		sb.append("  rm -f \"$RBDMAP_FILE.org\";");
		sb.append("fi;");
		
		// echo number of new volume
		sb.append("echo \"$id\";");
		this._template = sb.toString();
        setRunAsRoot(true);
    }
    
    public void setVolume(String pool, String volume, String snapshot) {
    	if (snapshot == null || snapshot.isEmpty())
    		snapshot = "-";
    	String cmd = String.format(_template, _monitors, _user, _key, pool, volume, snapshot);
    	setCommand(cmd);
    }

    @Override
    public void parseOutput() {
        String stdout = getOutput().getStdout();
        results = stdout.trim();
    }
}
