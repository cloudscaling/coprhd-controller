package com.emc.storageos.ceph;

import com.emc.storageos.svcs.errorhandling.annotations.DeclareServiceCode;
import com.emc.storageos.svcs.errorhandling.annotations.MessageBundle;
import com.emc.storageos.svcs.errorhandling.resources.ServiceCode;

@MessageBundle
public interface CephExceptions {

    @DeclareServiceCode(ServiceCode.CEPH_CONNECTION_ERROR)
    public CephException connectionError(Throwable t);

    @DeclareServiceCode(ServiceCode.CEPH_OPERATION_EXCEPTION)
    public CephException operationException(Throwable t);
}
