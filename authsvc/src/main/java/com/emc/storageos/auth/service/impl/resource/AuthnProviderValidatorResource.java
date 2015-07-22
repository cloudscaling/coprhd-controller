/*
 * Copyright 2015 EMC Corporation
 * All Rights Reserved
 */
/**
 *  Copyright (c) 2013 EMC Corporation
 * All Rights Reserved
 *
 * This software contains the intellectual property of EMC Corporation
 * or is licensed to EMC Corporation from third parties.  Use of this
 * software and the intellectual property contained therein is expressly
 * limited to the terms and conditions of the License Agreement under which
 * it is provided by or on behalf of EMC.
 */

package com.emc.storageos.auth.service.impl.resource;

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.auth.impl.ImmutableAuthenticationProviders;
import com.emc.storageos.coordinator.client.service.CoordinatorClient;
import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.keystone.restapi.KeystoneRestClientFactory;
import com.emc.storageos.model.auth.AuthnProviderParamsToValidate;

/**
 * resource to validate a Authentication provider by connecting
 * to it and performing various operations.  So far, just bind.
 */

@Path("/internal/authnProviderValidate")
public class AuthnProviderValidatorResource {  

    private final int DEFAULT_LDAP_CONNECTION_TIME_OUT_IN_SECS = 5;
    private int _ldapConnectionTimeoutInSecs = DEFAULT_LDAP_CONNECTION_TIME_OUT_IN_SECS;
    private CoordinatorClient coordinator;
    private KeystoneRestClientFactory keystoneApiFactory = null;
    private DbClient dbClient;


    private static final Logger _log = LoggerFactory.
            getLogger(AuthnProviderValidatorResource.class);


    public void setLdapConnectionTimeoutInSecs(int secs) {
        _ldapConnectionTimeoutInSecs = secs;
    }

    public void setCoordinator(CoordinatorClient coordinator) {
        this.coordinator = coordinator;
    }    
    
    public void setKeystoneFactory(KeystoneRestClientFactory factory)
    {
    	this.keystoneApiFactory = factory;
    }

    public void setDbClient(DbClient dbClient) {
        this.dbClient = dbClient;
    }

    /**
     * 
     * Validate if the basic parameters of a provider for connectivity
     * are good. Returns 400 if not.  200 if yes.
     * @param param has the connectivity parameters
     * @return Response 400 or 200
     */
    @POST
    @Produces({ MediaType.TEXT_PLAIN, MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON })
    public Response validateAuthenticationProvider(AuthnProviderParamsToValidate param) {
        StringBuilder errorString = new StringBuilder();
        if (!ImmutableAuthenticationProviders.checkProviderStatus(coordinator, param,
        		keystoneApiFactory, errorString, _ldapConnectionTimeoutInSecs, dbClient)) {
            return Response.status(Status.BAD_REQUEST).entity(errorString.toString()).build();
        } 
        return Response.status(Status.OK).build();
    }  
}
