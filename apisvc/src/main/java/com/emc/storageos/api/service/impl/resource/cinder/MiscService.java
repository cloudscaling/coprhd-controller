/**
 *  Copyright (c) 2015 EMC Corporation
 * All Rights Reserved
 *
 * This software contains the intellectual property of EMC Corporation
 * or is licensed to EMC Corporation from third parties.  Use of this
 * software and the intellectual property contained therein is expressly
 * limited to the terms and conditions of the License Agreement under which
 * it is provided by or on behalf of EMC.
 */

package com.emc.storageos.api.service.impl.resource.cinder;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.api.service.authorization.PermissionsHelper;
import com.emc.storageos.api.service.impl.resource.TaskResourceService;
import com.emc.storageos.api.service.impl.resource.utils.CinderApiUtils;
import com.emc.storageos.api.service.impl.response.ProjOwnedResRepFilter;
import com.emc.storageos.api.service.impl.response.ResRepFilter;
import com.emc.storageos.cinder.model.CinderAvailZonesResp;
import com.emc.storageos.cinder.model.CinderAvailabiltyZone;
import com.emc.storageos.cinder.model.CinderExtension;
import com.emc.storageos.cinder.model.CinderExtensionsRestResp;
import com.emc.storageos.cinder.model.CinderLimits;
import com.emc.storageos.cinder.model.CinderLimitsDetail;
import com.emc.storageos.cinder.model.UsageStats;
import com.emc.storageos.db.client.model.DataObject;
import com.emc.storageos.db.client.model.Project;
import com.emc.storageos.db.client.model.QuotaOfCinder;
import com.emc.storageos.db.client.model.VirtualArray;
import com.emc.storageos.db.client.model.VirtualPool;
import com.emc.storageos.model.RelatedResourceRep;
import com.emc.storageos.model.ResourceTypeEnum;
import com.emc.storageos.security.authentication.StorageOSUser;
import com.emc.storageos.security.authorization.ACL;
import com.emc.storageos.security.authorization.CheckPermission;
import com.emc.storageos.security.authorization.DefaultPermissions;
import com.emc.storageos.security.authorization.Role;
import com.emc.storageos.svcs.errorhandling.resources.APIException;


@Path("/v2/{tenant_id}")
@DefaultPermissions( read_roles = {Role.TENANT_ADMIN, Role.SYSTEM_MONITOR},
                     read_acls = {ACL.USE}, write_roles = { })
@SuppressWarnings({ "unchecked", "rawtypes" })
public class MiscService extends TaskResourceService {
    private static final Logger _log = LoggerFactory.getLogger(MiscService.class);
    private CinderHelpers helper = null;
    
    @Override
    public Class<CinderLimitsDetail> getResourceClass() {
        return CinderLimitsDetail.class;
    }
    
    
    private CinderHelpers getCinderHelper() {
		return CinderHelpers.getInstance(_dbClient , _permissionsHelper);
	}

    /**
     * Get Limits
     *     
     * @prereq none
     * @param tenant_id the URN of the tenant 
     * @return limits	
     */
    @GET
    @Produces({MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON})
    @Path("/limits")	
    @CheckPermission( roles = { Role.SYSTEM_MONITOR, Role.TENANT_ADMIN }, acls = {ACL.ANY})        
    public Response getLimits(@PathParam("tenant_id") String openstack_tenant_id, @Context HttpHeaders header) {    	
        _log.info("START get limits");
        CinderLimits limitsResp = new CinderLimits();                	     
    	Project project = getCinderHelper().getProject(openstack_tenant_id.toString() , getUserFromContext());
    	if(project == null){
        	throw APIException.badRequests.projectWithTagNonexistent(openstack_tenant_id);
        }
        int totalSizeUsed = 0;
    	int maxQuota = (int)QuotaService.DEFAULT_PROJECT_TOTALGB_QUOTA;
    	int maxTotalVolumes = 0;
    	int maxTotalSnapshots = 0;
    	int totalVolumesUsed = 0;
    	int totalSnapshotsUsed = 0;
    	
    	if(project.getQuotaEnabled()){
			maxQuota =(int) (project.getQuota().intValue());
		}
    	
    	UsageStats objUsageStats = new UsageStats();
    	objUsageStats = getCinderHelper().GetUsageStats(null, project.getId());
    			
    	totalVolumesUsed= (int) objUsageStats.volumes;
    	totalSnapshotsUsed= (int) objUsageStats.snapshots;
    	totalSizeUsed= (int) objUsageStats.spaceUsed;
    	
		QuotaOfCinder projQuota = getCinderHelper().getProjectQuota(openstack_tenant_id, getUserFromContext());
		if(projQuota!=null){
			maxTotalVolumes =  projQuota.getVolumesLimit().intValue();
			maxTotalSnapshots =  (int)projQuota.getSnapshotsLimit().intValue();						
		}
		else{
			QuotaOfCinder quotaObj = new QuotaOfCinder();	    	
	    	quotaObj.setId(URI.create(UUID.randomUUID().toString()));
	    	quotaObj.setProject(project.getId());	    	
	    	quotaObj.setVolumesLimit(QuotaService.DEFAULT_PROJECT_VOLUMES_QUOTA);
	    	quotaObj.setSnapshotsLimit(QuotaService.DEFAULT_PROJECT_SNAPSHOTS_QUOTA);
	    	quotaObj.setTotalQuota((long)maxQuota);	    		    	
	    	_dbClient.createObject(quotaObj);	    	
	    	_dbClient.persistObject(quotaObj);	
	    	maxTotalSnapshots = (int)QuotaService.DEFAULT_PROJECT_SNAPSHOTS_QUOTA;
	    	maxTotalVolumes = (int)QuotaService.DEFAULT_PROJECT_VOLUMES_QUOTA;	    		    	 	
		}
					        
        Map<String,Integer> absoluteDetailsMap = new HashMap<String,Integer>();               
        absoluteDetailsMap.put("totalSnapshotsUsed", totalSnapshotsUsed);
        absoluteDetailsMap.put("maxTotalVolumeGigabytes", maxQuota);
        absoluteDetailsMap.put("totalGigabytesUsed", totalSizeUsed);
        absoluteDetailsMap.put("maxTotalSnapshots", maxTotalSnapshots);
        absoluteDetailsMap.put("totalVolumesUsed", totalVolumesUsed);
        absoluteDetailsMap.put("maxTotalVolumes", maxTotalVolumes);         
        limitsResp.absolute = absoluteDetailsMap;    
        _log.info("END get limits");
        return CinderApiUtils.getCinderResponse(limitsResp, header, true);
    }
    
        
    /**
     * Get extensions
     *     
     * @prereq none
     * @param tenant_id the URN of the tenant 
     * @return extensions	
     */
    @GET
    @Produces({MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON})    
    @Path("/extensions")	
    @CheckPermission( roles = { Role.SYSTEM_MONITOR, Role.TENANT_ADMIN }, acls = {ACL.ANY})        
    public Response getExtensions(@PathParam("tenant_id") String openstack_tenant_id, @Context HttpHeaders header) {
    	// Here we ignore the openstack tenant id
        _log.info("START get extensions");
        CinderExtensionsRestResp extResp = new CinderExtensionsRestResp();
        CinderExtension objExt = new CinderExtension();
        objExt.alias = "os-availability-zone";
        objExt.description = "Describe Availability Zones.";
        objExt.namespace = "http://docs.openstack.org/volume/ext/os-availability-zone/api/v1";
        //TODO what do we do about the below hard coding?
        objExt.updated = "2013-06-27T00:00:00+00:00";  
        objExt.name = "AvailabilityZones";
        extResp.getExtensions().add(objExt);
        _log.info("END get extensions");
        return CinderApiUtils.getCinderResponse(extResp, header, false);
    }
    
    
    
    /**
     * Get availability zones
     *     
     * @prereq none
     * @param tenant_id the URN of the tenant 
     * @return availability zones
     */
    
    @GET
    @Produces({MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON})
    @Path("/os-availability-zone")	
    @CheckPermission( roles = { Role.SYSTEM_MONITOR, Role.TENANT_ADMIN }, acls = {ACL.ANY})        
    public CinderAvailZonesResp getAvailabilityZones(@PathParam("tenant_id") String openstack_tenant_id) {
    	// Here we ignore the openstack tenant id
        _log.info("START get availability zones");
        
        CinderAvailZonesResp objCinderAvailZonesResp = new CinderAvailZonesResp();                           
        List<CinderAvailabiltyZone> az_list = objCinderAvailZonesResp.getAvailabilityZoneInfo();
        
        _log.debug("retrieving virtual arrays via dbclient");
        
        getCinderHelper().getAvailabilityZones(az_list, getUserFromContext());
        
        return objCinderAvailZonesResp;   	
    }
    
    
    private CinderAvailabiltyZone extractAvailabilityZone(VirtualArray nh){
    	CinderAvailabiltyZone objAz = new CinderAvailabiltyZone();
    	objAz.zoneName = nh.getLabel();
    	objAz.zoneState.available = !nh.getInactive();
    	return objAz;
    }
    
    
    /**
     * Get object specific permissions filter
     *
     */
    @Override
    protected ResRepFilter<? extends RelatedResourceRep> getPermissionFilter(StorageOSUser user,
                                                                             PermissionsHelper permissionsHelper)
    {
        return new ProjOwnedResRepFilter(user, permissionsHelper, VirtualPool.class);
    }

	@Override
	protected DataObject queryResource(URI id) {
		throw new UnsupportedOperationException();
	}

	@Override
	protected URI getTenantOwner(URI id) { 
		throw new UnsupportedOperationException();
	}
	
	
	@Override
    protected ResourceTypeEnum getResourceType(){
		throw new UnsupportedOperationException();
    }
    
}
