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
		sb.append("monitors=\"%s\";");
		sb.append("user=\"%s\";");
		sb.append("key=\"%s\";");
		sb.append("pool=\"%s\";");
		sb.append("vol=\"%s\";");
		sb.append("snap=\"%s\";");
		sb.append("log=\"/tmp/coprhd.$pool.$vol.$snap.log\";");

		sb.append("if [[ \"$pool\" == \"-\" ]]; then");
		sb.append("  vol_path=\"/dev/rbd/$pool/$vol\";"); // pool/vol
		sb.append("else");
		sb.append("  vol_path=\"/dev/rbd/$pool/$vol@$snap\";"); // pool/vol@snap
		sb.append("fi;");

		sb.append("echo \"Map device: $vol_path\" > \"$log\";");
		
		// exit if already mounted
		sb.append("if [ -f \"$vol_path\" ]; then");
		sb.append("  echo Error: Device already mounted >> \"$log\";");
		sb.append("  exit -1;");
		sb.append("fi;");

		// current state
		sb.append("volumes=( $(ls /sys/bus/rbd/devices/ | sort) );");
		sb.append("size=${#volumes[@]};");
		sb.append("echo \"Current devices list: $volumes: (count=$size)\" >> \"$log\";");
		
		// do mapping
		sb.append("echo \"Do mount....\" >> \"$log\";");
		sb.append("echo \"$monitors name=$user,secret=$key $pool $vol $snap\" 1>\"/sys/bus/rbd/add\" 2>>\"$log\" || exit -1;");
		sb.append("echo \"Do mount.... Done\" >> \"$log\";");

		// detect volume id (number)
		sb.append("for i in {0..1}; do");
		sb.append("  echo Detect id try $i >> \"$log\";");
		sb.append("  v=$(readlink \"$vol_path\");");
		sb.append("  if [ ! -z \"$v\" ]; then break; fi;");
		sb.append("  sleep 1;");
		sb.append("done;");
		sb.append("id=$(echo \"$v\" | grep -o '[0-9]\\+');");
		sb.append("if [ -z \"$id\" ]; then");
		// old driver version
		sb.append("  echo Fallback to old driver >> \"$log\";");
		sb.append("  new_volumes=( $(ls /sys/bus/rbd/devices/ | sort) );");
		sb.append("  new_size=${#new_volumes[@]};");
		sb.append("  echo \"New devices list: $new_volumes: (count=$new_size)\" >> \"$log\";");
		sb.append("  index=0;");
		sb.append("  while [  $index -lt $new_size ]; do");
		sb.append("    v1=${new_volumes[$index]};");
		sb.append("    v2=${volumes[$index]};");
		sb.append("    echo \"Check index $index: v1=$v2, v2=$v2\" >> \"$log\";");
		sb.append("    if [[ $v1 != $v2 ]]; then");
		sb.append("      p=$(cat /sys/bus/rbd/devices/$v1/pool);");
		sb.append("      n=$(cat /sys/bus/rbd/devices/$v1/name);");
		sb.append("      s=$(cat /sys/bus/rbd/devices/$v1/current_snap);");		
		sb.append("      echo \"Check device: p=$p, n=$n s=$s\" >> \"$log\";");
		sb.append("      if [[ \"$p\" == \"$pool\" && \"$n\" == \"$vol\" && \"$s\" == \"$snap\" ]]; then");
		sb.append("        id=$v1;");
		sb.append("        break;");
		sb.append("      fi;");
		sb.append("    fi;");
		sb.append("    let index=$index+1;");
		sb.append("  done;");		
		sb.append("fi;");

		sb.append("if [ -z \"$id\" ]; then");
		sb.append("  echo Error: Device not found >> \"$log\";");
		sb.append("  exit -1;");
		sb.append("fi;");		
		
		// if ceph common installed (rbdmap service scripts) add info for persistent mapping (on reboot)
		sb.append("RBDMAP_FILE=\"/etc/ceph/rbdmap\";");
		sb.append("if [ -f \"$RBDMAP_FILE\" ]; then");
		sb.append("  echo \"Add device to $RBDMAP_FILE to enable automap on reboot\" >> \"$log\";");
		sb.append("  keyfile=\"/etc/ceph/$pool.$vol.$snap.keyfile\";");
		sb.append("  echo \"Create keyfile $keyfile\" >> \"$log\";");
		sb.append("  echo \"$key\" > \"$keyfile\";");
		sb.append("  yes | cp -f \"$RBDMAP_FILE\" \"$RBDMAP_FILE.org\";");
		sb.append("  yes | cp -f \"$RBDMAP_FILE\" \"$RBDMAP_FILE.new\";");
		sb.append("  echo \"$pool/$vol@$snap        id=$user,keyfile=$keyfile\" >> \"$RBDMAP_FILE.new\";");		
		sb.append("  mv -f \"$RBDMAP_FILE.new\" \"$RBDMAP_FILE\";");
		sb.append("  rm -f \"$RBDMAP_FILE.org\";");
		sb.append("fi;");
		
		// echo number of new volume
		sb.append("echo \"Device mapped with number $id\" >> \"$log\";");
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
