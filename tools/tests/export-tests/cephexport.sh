#!/bin/sh
#
# Copyright (c) 2015 EMC Corporation
# All Rights Reserved
#
#
# Export Test Suite for Cephs
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

BOURNE_IPS=${1:-$BOURNE_IPADDR}
IFS=',' read -ra BOURNE_IP_ARRAY <<< "$BOURNE_IPS"
IP_INDEX=0

BOURNE_IP=localhost

ethdev=`/sbin/ifconfig | grep Ethernet | head -1 | awk '{print $1}'`
macaddr=`/sbin/ifconfig $ethdev | /usr/bin/awk '/HWaddr/ { print $5 }'`
if [ "$macaddr" = "" ] ; then
    macaddr=`/sbin/ifconfig $ethdev | /usr/bin/awk '/ether/ { print $2 }'`
fi
#seed=`date "+%H%M%S%N"`
export BOURNE_API_SYNC_TIMEOUT=700

if [ "$BOURNE_IP" = "localhost" ]; then
    ipaddr=`/sbin/ifconfig $ethdev | /usr/bin/perl -nle 'print $1 if(m#inet addr:(.*?)\s+#);' | tr '.' '-'`
    SHORTENED_HOST="ip-$ipaddr"
fi
SHORTENED_HOST=${SHORTENED_HOST:=`echo $BOURNE_IP | awk -F. '{ print $1 }'`}

: ${TENANT=root}
: ${PROJECT=sanity_ceph}

RBD_CLI=/usr/bin/rbd

BASENUM=${RANDOM}
VOLNAME=EXPCephTest${BASENUM}
EXPORT_GROUP_NAME=EXPCephGroup${BASENUM}

VERIFY_COUNT=0
VERIFY_FAIL_COUNT=0


# ============================================================
# to help to configure ssh access by keys
validate_auto_ssh_access() {
    host=$1
    know_host=`grep $host ~/.ssh/known_hosts | wc -l`
    if [ $know_host -eq 0 ]; then
        if [ -e ~/.ssh/id_dsa ]; then
            echo SSH KEY has already been generated
        else
            echo We will have to generated the SSH KEY and push it to $host
            echo This should only be done once
            echo Accept all the defaults from ssh-keygen
            ssh-keygen
        fi
        echo Going to copy the SSH KEY to $host. Please type in the password when requested
        ssh-copy-id -i ~/.ssh/id_rsa.pub $host
    fi
    echo Checking if SSH KEY already on $host. If you get prompted for a password
    echo after this message, then it means that you will have to manually run the steps
    echo to copy the SSH KEY to $host. These are the steps:
    echo 1. ssh-keygen 2. ssh-copy-id -i /root/.ssh/id_rsa.pub $host
    ssh $host uname -a
}

validate_pool_exists() {
    local host=$1 
    local expected_pool=$2
    local pool=`ssh $host "ceph osd lspools | grep $expected_pool"`
    if [ -z $pool ]; then
        fail_test "Test expect that there is the pool $pool on ceph backend. You have to create it manually before test run."
    fi
}

validate_rbd_driver () {
    local host=$1 
    local drv=`ssh $host "/usr/sbin/lsmod | grep rbd"`
    if [ -z "$drv" ]; then
        ssh $host "/usr/sbin/modprobe rbd"
        drv=`ssh $host "lsmod | grep rbd"`
        if [ -z "$drv" ]; then
            fail_test "Test expect that RBD driver is loaded on the host $host. Test cant load it because of permissions. You have to do it manuall via 'sudo modprobe rbd' on the host."
        fi
    fi
}

# ============================================================
cleanupsnaps() {
   vol=$1
   echo "Deleting all snapshosts for volume: $vol"
   for id in `blocksnapshot list $vol | awk '/YES/ {print $6}'`
   do
      echo "Deleting snapshot: $id"
      runcmd blocksnapshot delete $id
   done
}

cleanupvols() {
   echo "Deleting all volumes in project $PROJECT"
   for id in `volume list $PROJECT | awk '/YES/ {print $7}'`
   do
      cleanupsnaps $id
      echo "Deleting volume: $id"
      runcmd volume delete --wait $id
   done
}

cleanup() {
   for id in `export_group list $PROJECT | awk '/YES/ {print $5}'`
   do
      echo "Deleted export group: $id"
      runcmd export_group delete $id
   done

   cleanupvols
}

fail_test() {
    msg=$1
    echo === FAILED: $msg
    VERIFY_FAIL_COUNT=`expr $VERIFY_FAIL_COUNT + 1`
    cleanup
    finish
}

finish() {
   echo There were $VERIFY_COUNT verifications
   echo There were $VERIFY_FAIL_COUNT verification failures
   exit $VERIFY_FAIL_COUNT
}

runcmd() {
    echo === $*
    $*
    if [ $? -ne 0 ]; then
       VERIFY_FAIL_COUNT=`expr $VERIFY_FAIL_COUNT + 1`
       echo === FAILED: $*
       cleanup
       finish
    fi
}

get_native_volume_id() {
    local id=$1
    local native_id=`volume show $id | awk '/"native_id"/ {print($2)}' | sed "s/[\"',]//g"`
    echo $native_id
}

get_native_snapshot_id() {
    local id=$1
    local native_id=`blocksnapshot show $id | awk '/"native_id"/ {print($2)}' | sed "s/[\"',]//g"`
    echo $native_id
}

verify_rbd_block_device() {
    local device=$1
    local host=$CEPH_IP

    VERIFY_COUNT=`expr $VERIFY_COUNT + 1`
 
    local expected_size=$2
    if [ -z "$expected_size" ]; then
        expected_size=$CEPH_VOLUME_SIZE_GB
    fi
        
    local actual_block_device=`ssh $host "$RBD_CLI ls -l --pool $CEPH_POOL | grep "$device""`
    
    if [ "$expected_size" = "gone" ]; then
        echo "verify $device is deleted (ceph host $host)"
        if [ -n "$actual_block_device" ]; then
            fail_test "Device $device is not deleted on the Ceph backend"
        fi
        echo PASSED: The device $device is deleted on ceph storage $host
    else
        echo "verify $device with expected size $expected_size (ceph host $host)"
        if [ -z "$actual_block_device" ]; then
            fail_test "There is no device with name $device on the Ceph backend"
        fi
        local size_mb=`echo $actual_block_device | awk '{print($2)}'`
        local size_gb=`expr $(echo $size_mb | sed 's/M//g') / 1024`
        if [ "$size_gb"GB != "$expected_size" ]; then
            fail_test "Volume expected size is $expected_size, but actual size is $size"
        fi
        echo PASSED: There is $device of size $expected_size on ceph storage $host
    fi    
}

verify_volume() {
    verify_rbd_block_device $*
}

verify_snapshot() {
    verify_rbd_block_device $*
}

verify_export() {
    host=$1
    expected_mapped_volumes=$2
    echo "verify exports: expected exported volumes $expected_mapped_volumes (ceph host $host)"

    VERIFY_COUNT=`expr $VERIFY_COUNT + 1`

    actual_volumes=`ssh $host "$RBD_CLI showmapped | awk '/^[0-9]/ {print(\\$5)}'"`
    actual_count=`echo "$actual_volumes" | wc -w`
    if [ x"$expected_mapped_volumes" = x"gone" ]; then
        if [ $actual_count -ne "0" ]; then
            fail_test "Expected no mappings for $host, but actual mappings count is $actual_count, mapped volumes are $actual_volumes."
        fi
        echo PASSED: No more volumes mapped to $host
    elif [ $expected_mapped_volumes -ne $actual_count ]; then
            fail_test "Expected $expected_mapped_volumes for $host, but found $actual_count"
    else
        echo PASSED: $expected_mapped_volumes volumes mapped to $host
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
    VARRAY=`neighborhood list | grep -o "$CEPH_VARRAY" | head -n 1`
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
   
   
    STORAGE_DEVICE=`storagedevice list | awk '/ceph/ {print($2)}'`
   
    # add storage device ports to network
    STORAGE_PORT=`storageport list $STORAGE_DEVICE | grep -o 'CEPH.*PORT+-'`
    echo Add storage port $STORAGE_PORT to network $NETWORK
    runcmd transportzone add $NETWORK "$STORAGE_PORT"

    # add host ports to network
    HOSTS=`hosts list "$TENANT" | awk '/urn/ {print($4)}'`
    for i in $HOSTS; do
        HOST_PORT=`initiator list $i | awk '/ceph/ {print($1)}'`
        echo Add host initiators $HOST_PORT to network $NETWORK
        runcmd transportzone add $NETWORK "$HOST_PORT"
    done
   
    # create virtual pool
    VPOOL=`cos list block | grep -o "$CEPH_VPOOL"`
    if [ -z "$VPOOL" ]; then
        VPOOL=$CEPH_VPOOL
        echo Create virtual pool $VPOOL for varray $VARRAY
        runcmd cos create block $VPOOL false --description='Ceph-VPool' --protocols=RBD --provisionType='Thin' --max_snapshots=8 --neighborhoods="$VARRAY" --expandable true --systemtype ceph
    else
        echo Use existing virtual pool $VPOOL
    fi
    
    # assign pool
    for i in `storagepool list $STORAGE_DEVICE  | grep -o "'name': 'CEPH.*POOL.*[0-9]'" | awk '{print($2)}' | sed "s/['""]//g"`; do
        POOL_NAME=`storagepool show $STORAGE_DEVICE/$i | awk '/pool_name/ {print(\$2)}' | sed 's/[,"'']//g'`
        if [ "$POOL_NAME" = "$CEPH_POOL" ]; then
            POOL_NATIVE_NAME=$i
            break
        fi
    done 
    if [ -z "$POOL_NATIVE_NAME" ]; then
        echo === FAILED: there is no storage pool $CEPH_POOL in storage system $STORAGE_DEVICE
        cleanup
        finish
    fi
    runcmd cos update_pools block $VPOOL "$STORAGE_DEVICE/$POOL_NATIVE_NAME"
    
    runcmd cos allow $VPOOL block $TENANT
}


# ============================================================
# Tests:

test_0() {
    echo "${FUNCNAME} Begins: create/list/delete volumes"
    runcmd volume create $VOLNAME $PROJECT $CEPH_VARRAY $CEPH_VPOOL $CEPH_VOLUME_SIZE_GB --count 4

    volumes=`volume list $PROJECT | grep $VOLNAME | awk '{print($7)}'`
    if [ -z "volumes" ]; then
        fail_test "There are no expected volumes ${VOLNAME}-1, ${VOLNAME}-2"
    fi

    for id in $volumes; do
        native_id=`volume show $id | awk '/native_id/ {print($2)}' | sed "s/[\"',]//g"`
        verify_volume $native_id
        runcmd volume delete --wait $id
        verify_volume $native_id "gone"        
    done
}

test_1() {
    echo "${FUNCNAME} Begins: Expand volume"
    runcmd volume create $VOLNAME $PROJECT $CEPH_VARRAY $CEPH_VPOOL $CEPH_VOLUME_SIZE_GB --count 1

    id=`volume list $PROJECT | grep $VOLNAME | awk '{print($7)}' `
    native_id=$(get_native_volume_id $id)
    verify_volume $native_id

    runcmd volume expand $id $CEPH_EXPAND_VOLUME_SIZE_GB
    verify_volume $native_id $CEPH_EXPAND_VOLUME_SIZE_GB

    runcmd volume delete --wait $id
    verify_volume $id "gone"        
}

test_2() {
    echo "${FUNCNAME} Begins: Full copy volume"
    runcmd volume create $VOLNAME $PROJECT $CEPH_VARRAY $CEPH_VPOOL $CEPH_VOLUME_SIZE_GB --count 1

    id=`volume list $PROJECT | grep $VOLNAME | awk '{print($7)}' `
    native_id=$(get_native_volume_id $id)
    verify_volume $native_id

    fullcopy_name="$VOLNAME"-FullCopy
    runcmd volume full_copy $fullcopy_name $id
    
    fc_id=`volume list $PROJECT | grep $fullcopy_name | awk '{print($7)}' `
    native_id=$(get_native_volume_id $fc_id)
    verify_volume $native_id

    runcmd volume delete --wait $id
    runcmd volume delete --wait $fc_id
    verify_volume $fc_id "gone"        
    verify_volume $id "gone"        
}

test_3() {
    echo "${FUNCNAME} Begins: Export/unexport volume"
    runcmd volume create $VOLNAME $PROJECT $CEPH_VARRAY $CEPH_VPOOL $CEPH_VOLUME_SIZE_GB --count 2

    volumes=`volume list $PROJECT | grep $VOLNAME | awk '{print($7)}'`
    if [ -z "volumes" ]; then
        fail_test "There are no expected volumes ${VOLNAME}-1, ${VOLNAME}-2"
    fi
    
    for id in $volumes; do
        native_id=$(get_native_volume_id $id)
        verify_volume $native_id
    done

    expname=$EXPORT_GROUP_NAME
    runcmd export_group create $PROJECT $expname $CEPH_VARRAY --type Host --volspec ${PROJECT}/${VOLNAME}-1,${PROJECT}/${VOLNAME}-2 --hosts "$CEPH_HOSTS"
    for host in `echo $CEPH_HOSTS | sed 's/,/ /g'`; do    
        verify_export $host 2
        runcmd export_group delete $PROJECT/$expname
        verify_export $host gone
    done

    for id in $volumes; do
        runcmd volume delete --wait $id
        verify_volume $id "gone"        
    done
}

test_4() {
    echo "${FUNCNAME} Begins: create/list/delete snapshot"
    runcmd volume create $VOLNAME $PROJECT $CEPH_VARRAY $CEPH_VPOOL $CEPH_VOLUME_SIZE_GB --count 1

    id=`volume list $PROJECT | grep $VOLNAME | awk '{print($7)}' `
    native_id=$(get_native_volume_id $id)
    verify_volume $native_id

    snap_name="$VOLNAME"-Snap
    runcmd blocksnapshot create $id $snap_name
    
    snap_id=`blocksnapshot list $id | grep $snap_name | awk '{print($6)}'`
    native_id=$(get_native_snapshot_id $snap_id)
    verify_snapshot $native_id

    runcmd blocksnapshot delete $snap_id
    verify_snapshot $snap_id "gone"        

    runcmd volume delete --wait $id
    verify_volume $id "gone"        
}

test_5() {
    echo "${FUNCNAME} Begins: export/unexport snapshot"
    runcmd volume create $VOLNAME $PROJECT $CEPH_VARRAY $CEPH_VPOOL $CEPH_VOLUME_SIZE_GB --count 1

    id=`volume list $PROJECT | grep $VOLNAME | awk '{print($7)}' `
    native_id=$(get_native_volume_id $id)
    verify_volume $native_id

    snap_name="$VOLNAME"-Snap
    runcmd blocksnapshot create $id $snap_name
    
    snap_id=`blocksnapshot list $id | grep $snap_name | awk '{print($6)}'`
    native_id=$(get_native_snapshot_id $snap_id)
    verify_snapshot $native_id

    expname=$EXPORT_GROUP_NAME
    runcmd export_group create $PROJECT $expname $CEPH_VARRAY --type Host --volspec ${PROJECT}/${VOLNAME}/${snap_name} --hosts "$CEPH_HOSTS"
    for host in `echo $CEPH_HOSTS | sed 's/,/ /g'`; do    
        verify_export $host 1
        runcmd export_group delete $PROJECT/$expname
        verify_export $host gone
    done
    
    runcmd blocksnapshot delete $snap_id
    verify_snapshot $snap_id "gone"        

    runcmd volume delete --wait $id
    verify_volume $id "gone"        
}

test_all() {
    for i in {0..5}; do
        test_$i
    done
}

run_test() {
    #check prerequisites
    validate_auto_ssh_access $CEPH_IP
    validate_pool_exists $CEPH_IP $CEPH_POOL
    for host in `echo $CEPH_HOSTS | sed 's/,/ /g'`; do    
        validate_auto_ssh_access $host
        validate_rbd_driver $host
    done
    
    # run test
    test_$1
}

# ============================================================
usage() {
    echo "Usage: `basename $0` <sanity_config_file> <cmd: setup|regression|cleanupvols|delete|run> [test_number_to_run: 0|1|...]"
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

login

if [ "$1" = "regression" ]
then
   run_test "0";
fi

if [ "$1" = "cleanupvols" ]
then
  cleanupvols
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
        run_test $2
        cleanup
        finish
    fi
fi

usage
finish
