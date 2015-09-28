/*
 * Copyright 2015 EMC Corporation
 * All Rights Reserved
 */

package com.emc.storageos.ecs.api;

import java.net.URI;

import com.emc.storageos.svcs.errorhandling.annotations.DeclareServiceCode;
import com.emc.storageos.svcs.errorhandling.annotations.MessageBundle;
import com.emc.storageos.svcs.errorhandling.resources.ServiceCode;

@MessageBundle
public interface ECSExceptions {

    @DeclareServiceCode(ServiceCode.ECS_RETURN_PARAM_ERROR)
    public ECSException invalidBaseURI(final String ip, final String port);

    @DeclareServiceCode(ServiceCode.ECS_CONNECTION_ERROR)
    public ECSException unableToConnect(final URI baseUrl, final int status);

    @DeclareServiceCode(ServiceCode.ECS_RETURN_PARAM_ERROR)
    public ECSException invalidReturnParameters(final URI baseUrl);

    @DeclareServiceCode(ServiceCode.ECS_LOGINVALIDATE_ERROR)
    public ECSException isSystemAdminFailed(final URI baseUrl, final int status);

    @DeclareServiceCode(ServiceCode.ECS_STORAGEPOOL_ERROR)
    public ECSException storageAccessFailed(final URI baseUrl, final int status, final String info);

    @DeclareServiceCode(ServiceCode.ECS_STATS_ERROR)
    public ECSException getStoragePoolsFailed(final String response, final Throwable cause);

    @DeclareServiceCode(ServiceCode.ECS_STATS_ERROR)
    public ECSException createBucketFailed(final String response, final Throwable cause);

    @DeclareServiceCode(ServiceCode.ECS_NON_SYSTEM_ADMIN_ERROR)
    public ECSException discoverFailed(final String response);

    @DeclareServiceCode(ServiceCode.ECS_CONNECTION_ERROR)
    public ECSException errorCreatingServerURL(final String host, final int port, final Throwable e);

    @DeclareServiceCode(ServiceCode.ECS_BUCKET_UPDATE_ERROR)
    public ECSException bucketUpdateFailed(final String bucketName, final String attributeType, final String message);

    @DeclareServiceCode(ServiceCode.ECS_BUCKET_DELETE_ERROR)
    public ECSException bucketDeleteFailed(final String bucketName, final String info);

}
