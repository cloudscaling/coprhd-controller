#!/bin/sh
#
# Copyright (c) 2015 EMC Corporation
# All Rights Reserved
#
#
# Export Test Suite for ScaleIO
#
#
# set -x

if [ "$DEBUG_SCRIPT" = "1" ]; then 
  set -x
fi 

SANITY_CONFIG_FILE=""

# The token file name will have a suffix which is this shell's PID
# It will allow to run the sanity in parallel
export BOURNE_TOKEN_FILE="/tmp/token$$.txt"
BOURNE_SAVED_TOKEN_FILE="/tmp/token_saved.txt"

PATH=$(dirname $0):$(dirname $0)/..:/bin:/usr/bin:

#BOURNE_IPS=${1:-$BOURNE_IPADDR}
#IFS=',' read -ra BOURNE_IP_ARRAY <<< "$BOURNE_IPS"
BOURNE_IP=localhost
#IP_INDEX=0

ethdev=`/sbin/ifconfig | grep Ethernet | head -1 | awk '{print $1}'`
macaddr=`/sbin/ifconfig $ethdev | /usr/bin/awk '/HWaddr/ { print $5 }'`
if [ "$macaddr" = "" ] ; then
    macaddr=`/sbin/ifconfig $ethdev | /usr/bin/awk '/ether/ { print $2 }'`
fi
seed=`date "+%H%M%S%N"`
ipaddr=`/sbin/ifconfig $ethdev | /usr/bin/perl -nle 'print $1 if(m#inet addr:(.*?)\s+#);' | tr '.' '-'`
export BOURNE_API_SYNC_TIMEOUT=700

if [ "$BOURNE_IP" = "localhost" ]; then
    SHORTENED_HOST="ip-$ipaddr"
fi
SHORTENED_HOST=${SHORTENED_HOST:=`echo $BOURNE_IP | awk -F. '{ print $1 }'`}
: ${TENANT=root}
: ${PROJECT=sanity}
RBD_CLI=/usr/bin/rbd

# ============================================================
# - Export testing parameters                                -
# ============================================================
BASENUM=${RANDOM}
VOLNAME=EXPTest${BASENUM}
BLOCKSNAPSHOT_NAME=EXPTestSnap${BASENUM}
EXPORT_GROUP_NAME=export${BASENUM}
PROJECT=project

# ============================================================
# Ceph CLI access is through SSH. In order to make this
# automated, we would need to have the DevKit VM's SSH keys
# copied over to the known/authorized host list on the
# ScaleIO MDM host. This method runs these steps (if they
# haven't already been done.
# ============================================================
validate_auto_ssh_access() {
    know_host=`grep "$CEPH_IP" ~/.ssh/known_hosts | wc -l`
    if [ $know_host -eq 0 ]; then
        if [ -e /root/.ssh/id_dsa ]; then
            echo SSH KEY has already been generated
        else
            echo We will have to generated the SSH KEY and push it to $CEPH_IP
            echo This should only be done once
            echo Accept all the defaults from ssh-keygen
            ssh-keygen
        fi
        echo Going to copy the SSH KEY to $CEPH_IP. Please type in the password when requested
        ssh-copy-id -i /root/.ssh/id_rsa.pub $CEPH_IP
    fi
    echo Checking if SSH KEY already on $CEPH_IP. If you get prompted for a password
    echo after this message, then it means that you will have to manually run the steps
    echo to copy the SSH KEY to $CEPH_IP. These are the steps:
    echo 1. ssh-keygen 2. ssh-copy-id -i /root/.ssh/id_rsa.pub $CEPH_IP
    ssh z uname -a
}

VERIFY_EXPORT_COUNT=0
VERIFY_EXPORT_FAIL_COUNT=0
verify_export() {
    host=$1
    expected_mapped_volumes=$2
    VERIFY_EXPORT_COUNT=`expr $VERIFY_EXPORT_COUNT + 1`
    actual_volumes=`ssh $CEPH_IP "$RBD_CLI showmapped | awk '{/^[0-9]/ print(\\$5)}'"`
    actual_count=`echo "$actual_volumes" | wc -l`
    if [ x"$expected_mapped_volumes" = x"gone" ]; then
        if [ $actual_count -ne "0" ]; then
            echo === FAILED: There was a failure. Expected no mappings for $host, but there were $actual_count
            echo === FAILED: The results of the last ScaleIO CLI command: $actual_volumes
            VERIFY_EXPORT_FAIL_COUNT=`expr $VERIFY_EXPORT_FAIL_COUNT + 1`
            cleanup
            finish
        fi
        echo PASSED: No more volumes mapped to $host
    elif [ $expected_mapped_volumes -ne $actual_count ]; then
            echo === FAILED: Expected $expected_mapped_volumes for $host, but found $actual_count
            VERIFY_EXPORT_FAIL_COUNT=`expr $VERIFY_EXPORT_FAIL_COUNT + 1`
            cleanup
            finish
    else
        echo PASSED: $expected_mapped_volumes volumes mapped to $host
    fi
}

runcmd() {
    echo === $*
    $*
    if [ $? -ne 0 ]; then
       VERIFY_EXPORT_FAIL_COUNT=`expr $VERIFY_EXPORT_FAIL_COUNT + 1`
       echo === FAILED: $*
       cleanup
       finish
    fi
}

login() {
    security login $SYSADMIN $SYSADMIN_PASSWORD
    syssvc $SANITY_CONFIG_FILE localhost setup
    if [ "${LOCAL_LDAP_SERVER_IP}" = "X.X.X.X" ]; then
        echo Ldap server not provided, so continue without LDAP
    else
        security add_authn_provider ldap ldap://${LOCAL_LDAP_SERVER_IP} cn=manager,dc=viprsanity,dc=com secret    ou=ViPR,dc=viprsanity,dc=com uid=%U CN Local_Ldap_Provider VIPRSANITY.COM ldapViPR* SUBTREE --group_object_classes groupOfNames,groupOfUniqueNames,posixGroup,organizationalRole --group_member_attributes member,uniqueMember,memberUid,roleOccupant
    fi
    
    echo "Tenant is ${TENANT}";
    if [ "$TENANT" = "root" ]; then
        TENANT=`tenant root | head -1`
    fi
}


set_hosts() {
    HOSTS=`hosts list $TENANT`
    for i in `echo $CEPH_HOSTS | sed 's/,/ /g'`; do
        HOST=$i
        HOST_NAME=`echo $HOSTS | grep -o "$HOST"`
        if [ -z "$HOST_NAME" ]; then
            echo Create host 
            runcmd hosts create "$HOST" "$TENANT" Linux "$HOST" --port 22 --username $CEPH_HOST_USER_NAME --password $CEPH_HOST_PASSWORD --discoverable true
        else
            echo Use existing host $HOST
        fi
     done
}

setup() {
    sleep 15
    
    # create project for tenant
    prj=`project list | grep -o "$PROJECT"`
    if [ -z "$prj" ]; then
        echo Create project $PROJECT for tenant $TENANT
        runcmd project create $PROJECT --tenant $TENANT
        prj=$PROJECT
    else
        echo Use existing project $prj
    fi

    # create storage provider and do discovery
    provider=`storageprovider list | grep -o "$CEPH_PROVIDER"`
    if [ -z "$provider" ]; then
        echo Create storage provider $CEPH_PROVIDER with IP = $CEPH_IP
        runcmd storageprovider create $CEPH_PROVIDER $CEPH_IP 22 $CEPH_USER "fake_password" ceph --keyring_key "$CEPH_KEY"
        provider=$CEPH_PROVIDER
    else
        echo Use existing storage provider $provider
    fi
    runcmd storagedevice discover_all
    sleep 60

    set_hosts;

    # create virtual array
    VARRAY=`neighborhood list | grep -o "$CEPH_VARRAY"`
    if [ -z "$VARRAY" ]; then
        echo Create VArray $CEPH_VARRAY
        runcmd neighborhood create $CEPH_VARRAY
        VARRAY=$CEPH_VARRAY
    else
        echo Use existing VArray $VARRAY
    fi

    # create network
    NETWORK=`transportzone listall | grep -o "$CEPH_NETWORK"`
    if [ -z "$NETWORK" ]; then
        echo Create network $CEPH_NETWORK for varray $VARRAY
        runcmd transportzone create $CEPH_NETWORK $VARRAY --type IP
        NETWORK=$CEPH_NETWORK
    else
        echo Use existing network $NETWORK
    fi
    runcmd transportzone assign $NETWORK $VARRAY
    runcmd neighborhood allow $VARRAY $TENANT
   
    # add storage device ports to network
    STORAGE_PORT=`./storageport list $(./storagedevice list | awk '/ceph/ {print($2)}') | grep -o 'CEPH.*PORT+-'`
    echo Add storage port $STORAGE_PORT to network $NETWORK
    runcmd transportzone add $NETWORK "$STORAGE_PORT"

    # add host ports to network
    HOSTS=`./hosts list "$TENANT" | awk '/urn/ {print($4)}'`
    for i in $HOSTS; do
        HOST_PORT=`./initiator list $i | awk '/ceph/ {print($1)}'`
        echo Add host initiators $HOST_PORT to network $NETWORK
        runcmd transportzone add $NETWORK "$HOST_PORT"
    done
   
    # create virtual pool
    VPOOL=`cos list block | grep -o "$CEPH_VPOOL"`
    if [ -z "$VPOOL" ]; then
        echo Create virtual pool $CEPH_VPOOL for varray $VARRAY
        runcmd cos create block $CEPH_VPOOL true --description='Ceph-VPool' --protocols=RBD --provisionType='Thin' --max_snapshots=8 --neighborhoods="$VARRAY" --expandable
        VPOOL=$CEPH_VPOOL
    else
        echo Use existing virtual pool $VPOOL
    fi
    storagedevice list | grep COMPLETE | awk '{ print($2) }' | xargs -i cos update block $VPOOL --storage {}
    storagedevice list | grep COMPLETE | awk '{ print($2) }' | xargs -i storagepool update {} --nhadd $VARRAY --pool $VPOOL --type block --volume_type THIN
    storagedevice list | grep COMPLETE | awk '{ print($2) }' | xargs -i storageport update {} Ceph --tzone $VARRAY/$NETWORK
    

    runcmd cos allow ${VPOOL} block $TENANT
    runcmd volume create ${VOLNAME} ${PROJECT} ${VARRAY} ${VPOOL} 1GB --count 4
}

deletevols() {
   for id in `volume list project | grep YES | awk '{print $7}'`
   do
      echo "Deleting volume: ${id}"
      runcmd volume delete ${id} > /dev/null
   done
}

cleanup() {
   for id in `export_group list project | grep YES | awk '{print $5}'`
   do
      echo "Deleted export group: ${id}"
      runcmd export_group delete ${id} > /dev/null
   done
   volume delete $PROJECT --project --wait
   echo There were $VERIFY_EXPORT_COUNT export verifications
   echo There were $VERIFY_EXPORT_FAIL_COUNT export verification failures
}

finish() {
   if [ $VERIFY_EXPORT_FAIL_COUNT -ne 0 ]; then 
       exit $VERIFY_EXPORT_FAIL_COUNT
   fi
   exit 0
}

# Export Test 0
#
# Most basic test. Create an export with hosts and volumes
#
test_0() {
    echo "Test 0 Begins"
    expname=${EXPORT_GROUP_NAME}_t0
    runcmd export_group create $PROJECT ${expname}1 $CEPH_VARRAY --type Host --volspec ${PROJECT}/${VOLNAME}-1,${PROJECT}/${VOLNAME}-2 --hosts "$CEPH_HOSTS"
    for i in `echo $CEPH_HOSTS | sed 's/,/ /g'`; do    
        verify_export $i 2
        runcmd export_group delete $PROJECT/${expname}1
        verify_export $i gone
    done
}

usage() {
    echo "Usage: `basename $0` <sanity_config_file> <cmd: setup|regression|deletevol|delete|run> [test_number_to_run: 0|1|...]"
}

# ============================================================
# -    M A I N
# ============================================================

# ============================================================
# Check if there is a sanity configuration file specified 
# on the command line. In, which case, we should use that 
# ============================================================

if [ -z "$1" ]; then
    usage
    finish
fi

if [ -f "$1" ]; then 
    SANITY_CONFIG_FILE=$1
    echo Using sanity configuration file $SANITY_CONFIG_FILE
    source $SANITY_CONFIG_FILE
    shift
else
    echo First parameter should be saity config file
    usage
    finish
fi

#TODO: un-comment after adding checks on remote hosts
#validate_auto_ssh_access
login

if [ "$1" = "regression" ]
then
   test_0;
fi

if [ "$1" = "deletevol" ]
then
  deletevols
  finish
fi

if [ "$1" = "delete" ]
then
  cleanup
  finish
fi

if [ "$1" = "setup" ]
then
    setup;
    finish
fi

if [ "$1" = "run" ]
then
    # If there's a 2nd parameter, take that
    # as the name of the test to run
    if [ "$2" != "" ]
    then
        echo Request to run test_$2
        test_$2
        cleanup
        finish
    fi
fi

usage
finish
