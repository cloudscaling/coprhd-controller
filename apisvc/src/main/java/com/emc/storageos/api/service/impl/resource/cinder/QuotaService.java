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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.ws.rs.GET;
import javax.ws.rs.PUT;
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
import com.emc.storageos.cinder.model.CinderQuotaDetails;
import com.emc.storageos.db.client.model.DataObject;
import com.emc.storageos.db.client.model.Project;
import com.emc.storageos.db.client.model.QuotaOfCinder;
import com.emc.storageos.db.client.model.VirtualPool;
import com.emc.storageos.db.client.model.Volume;
import com.emc.storageos.model.RelatedResourceRep;
import com.emc.storageos.model.ResourceTypeEnum;
import com.emc.storageos.security.authentication.StorageOSUser;
import com.emc.storageos.security.authorization.ACL;
import com.emc.storageos.security.authorization.CheckPermission;
import com.emc.storageos.security.authorization.DefaultPermissions;
import com.emc.storageos.security.authorization.Role;
import com.emc.storageos.svcs.errorhandling.resources.APIException;

@Path("/v2/{tenant_id}/os-quota-sets")
		
@DefaultPermissions( read_roles = { Role.SYSTEM_MONITOR, Role.TENANT_ADMIN },
        read_acls = {ACL.OWN, ACL.ALL},
        write_roles = { Role.TENANT_ADMIN },
        write_acls = {ACL.OWN, ACL.ALL})
@SuppressWarnings({ "unchecked", "rawtypes" })
public class QuotaService extends TaskResourceService {

    private static final Logger _log = LoggerFactory.getLogger(QuotaService.class);
    private static final String EVENT_SERVICE_TYPE = "block";    
    private CinderHelpers helper = null;  
    public static final long DEFAULT_VOLUME_TYPE_SNAPSHOTS_QUOTA = -1;
    public static final long DEFAULT_VOLUME_TYPE_VOLUMES_QUOTA= -1;
    public static final long DEFAULT_VOLUME_TYPE_TOTALGB_QUOTA = -1;      
    public static final long DEFAULT_PROJECT_TOTALGB_QUOTA = 1000000;
    //Taken from product support matrix of vipr
    public static final long DEFAULT_PROJECT_SNAPSHOTS_QUOTA = 3000;
    //Taken from product support matrix of vipr
    public static final long DEFAULT_PROJECT_VOLUMES_QUOTA = 6000;
    
    private CinderHelpers getCinderHelper() {
		return CinderHelpers.getInstance(_dbClient , _permissionsHelper);
	}

    /**
     * Get the summary list of all Quotas for the given tenant
     *     
     *
     * @prereq none
     *
     * @param tenant_id the URN of the tenant asking for quotas 
     * @param target_tenant_id 
     * @brief 
     * @return Quota details of target_tenant_id
     */
    @GET    
    @Produces({MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON})
    
    @Path("/{target_tenant_id}")
    @CheckPermission( roles = { Role.SYSTEM_MONITOR, Role.TENANT_ADMIN }, acls = {ACL.ANY})
    public Response getQuotaDetails(
    		@PathParam("target_tenant_id") String openstack_target_tenant_id, @Context HttpHeaders header) {
    	
        Project project = getCinderHelper().getProject(openstack_target_tenant_id.toString() ,
                                            getUserFromContext());

        if(project == null){
        	throw APIException.badRequests.projectWithTagNonexistent(openstack_target_tenant_id);
        }
        
        long maxQuota = 0;
        if(project.getQuotaEnabled()){
            maxQuota = (long) (project.getQuota().intValue());
        }
        else{
            maxQuota = DEFAULT_PROJECT_TOTALGB_QUOTA;
        }
           
    	List<URI> quotas= _dbClient.queryByType(QuotaOfCinder.class, true);
    	Map<String, String> vpoolsMap = new HashMap<String,String>();
    	boolean bDefProjQuotasExist = false;
    	
    	CinderQuotaDetails	respCinderQuota  = new CinderQuotaDetails();
    	    	    
    	for(URI quota : quotas){    		
    		QuotaOfCinder quotaObj = _dbClient.queryObject(QuotaOfCinder.class, quota);    		
    		    		    		
    		if( (quotaObj.getProject() != null) && 
    				(quotaObj.getProject().toString().equalsIgnoreCase(project.getId().toString())) ){
    			if(quotaObj.getVpool() !=null ){
    				VirtualPool pool = _dbClient.queryObject(VirtualPool.class, quotaObj.getVpool());
        			respCinderQuota.quota_set.put("gigabytes" + "_" + pool.getLabel() ,  String.valueOf(quotaObj.getTotalQuota()));
        	    	respCinderQuota.quota_set.put("snapshots" + "_" + pool.getLabel() , String.valueOf(quotaObj.getSnapshotsLimit()));
        	    	respCinderQuota.quota_set.put("volumes" + "_" + pool.getLabel() , String.valueOf(quotaObj.getVolumesLimit()));
        	    	vpoolsMap.put(pool.getLabel(), pool.getLabel());
    			}
    			else{	    			
	    			respCinderQuota.quota_set.put("gigabytes", String.valueOf(quotaObj.getTotalQuota()));
	    	    	respCinderQuota.quota_set.put("snapshots", String.valueOf(quotaObj.getSnapshotsLimit()));
	    	    	respCinderQuota.quota_set.put("volumes", String.valueOf(quotaObj.getVolumesLimit().intValue()));
	    	    	bDefProjQuotasExist = true;
    			}
    		}    		 		  	    		       
    	}
    	
    	if(!bDefProjQuotasExist){    		    		
    		respCinderQuota.quota_set.put("gigabytes",  String.valueOf(maxQuota));
	    	respCinderQuota.quota_set.put("snapshots", String.valueOf(DEFAULT_PROJECT_SNAPSHOTS_QUOTA));
	    	respCinderQuota.quota_set.put("volumes", String.valueOf(DEFAULT_PROJECT_VOLUMES_QUOTA));	    	
	    	getCinderHelper().createProjectDefaultQuota(project);    	
    	}
    	    	
    	StorageOSUser user = getUserFromContext();
        URI tenantId = URI.create(user.getTenantId());
    	
    	List<URI> vpools = _dbClient.queryByType(VirtualPool.class, true);
        for (URI vpool: vpools){
        	VirtualPool pool = _dbClient.queryObject(VirtualPool.class, vpool);
            _log.debug("Looking up vpool {}", pool.getLabel());
        	if (pool != null && pool.getType().equalsIgnoreCase(VirtualPool.Type.block.name())) {
        		if (_permissionsHelper.tenantHasUsageACL(tenantId, pool)){
                    if(vpoolsMap.containsKey(pool.getLabel()) ){
                    	continue;
                    }
                    else{                        	
                    	respCinderQuota.quota_set.put("gigabytes" + "_" + pool.getLabel() ,String.valueOf(DEFAULT_VOLUME_TYPE_TOTALGB_QUOTA));
            	    	respCinderQuota.quota_set.put("snapshots" + "_" + pool.getLabel() , String.valueOf(DEFAULT_VOLUME_TYPE_SNAPSHOTS_QUOTA));
            	    	respCinderQuota.quota_set.put("volumes" + "_" + pool.getLabel() , String.valueOf(DEFAULT_VOLUME_TYPE_VOLUMES_QUOTA));      
            	    	getCinderHelper().createVpoolDefaultQuota(project , pool);            	    
                    }
        		}
        	}
        }
        
    	return getQuotaDetailFormat(header, respCinderQuota);
    	
    }
    
    
    
    /**
     * Update a quota
     *     
     *
     * @prereq none
     *
     * @param tenant_id the URN of the tenant 
     * @param target_tenant_id the URN of the target tenant 
     * for which quota is being modified  
     *
     * @brief Update Quota
     * @return Quota details of target_tenant_id
     */
    @PUT
    @Produces({MediaType.APPLICATION_XML, MediaType.APPLICATION_JSON})
    @Path("/{target_tenant_id}")
    @CheckPermission( roles = { Role.SYSTEM_MONITOR, Role.TENANT_ADMIN }, acls = {ACL.ANY})
    public Response updateQuota(@PathParam("tenant_id") String openstack_tenant_id,
    		   @PathParam("target_tenant_id") String openstack_target_tenant_id,    		   
    		   CinderQuotaDetails quotaUpdates, @Context HttpHeaders header) {
    	
       _log.info("Updating Quota");
       Project project = getCinderHelper().getProject(openstack_target_tenant_id.toString() ,
                getUserFromContext());
       if(project == null){
        	throw APIException.badRequests.projectWithTagNonexistent(openstack_target_tenant_id);
       }
    	
	   long maxQuota = 0L;
       if(project.getQuotaEnabled())
       {
           maxQuota = (long) (project.getQuota().intValue());
       }
       else
       {
           maxQuota = DEFAULT_PROJECT_TOTALGB_QUOTA;
       }
               
       //bVpoolQuotaUpdate will be set to true if the user is updating the quota of a vpool w.r.t a project
       //bVpoolQuotaUpdate will be set to false if the user is updating the quota of the project
       boolean bVpoolQuotaUpdate = isVpoolQuotaUpdate(quotaUpdates.quota_set);
       String vpoolName = null;
       VirtualPool objVpool = null;
       
       	if(bVpoolQuotaUpdate){
       		vpoolName = getVpoolName(quotaUpdates.quota_set);
       		_log.info("Vpool for which quota is being updated is {}", vpoolName);
       		objVpool = getCinderHelper().getVpool(vpoolName);

       		if(objVpool==null){
       			_log.info("vpool with the given name doesnt exist");
       			throw APIException.badRequests.parameterIsNotValid(vpoolName);
       		}
       		_log.info("objVpool.getLabel() is {}", objVpool.getLabel());
       	}
       	       	    	
    	List<URI> quotas= _dbClient.queryByType(QuotaOfCinder.class, true);    	    	    	    	    
    	boolean noEntriesInDB = true;
    	for(URI quota : quotas){    		
    		QuotaOfCinder quotaObj = _dbClient.queryObject(QuotaOfCinder.class, quota);
    		if( (quotaObj.getProject() != null) && 
    				(quotaObj.getProject().toString().equalsIgnoreCase(project.getId().toString())) ){
    			_log.info("QuotaObj being updated is {}", quotaObj.toString());
    			
    			URI vpoolUri = quotaObj.getVpool();
    			

    			if( (!bVpoolQuotaUpdate) && (vpoolUri != null) ){
        			//The user requested update of project quota. 
        			//But the current db entry quota for vpool w.r.t project.
        			//Hence just skip the db entry as this is not our concern.    				
    				continue;    				
    			}
    			    			
    			if( (bVpoolQuotaUpdate) &&  (vpoolUri != null) ){
    				//The user requested quota update for a vpool w.r.t a project. 
    				//The current db entry that we looking into has vpool entry. 
    				//Hence we should further check if the vpool value is same as the vpool for which the user wants to set quota
    				VirtualPool pool = _dbClient.queryObject(VirtualPool.class, vpoolUri);    				
	    			if( (pool!= null) && (pool.getLabel().equals(vpoolName)) && (vpoolName!=null)  && (vpoolName.length()>0)){
	    		
	    				if(quotaUpdates.quota_set.containsKey("gigabytes_"+vpoolName))
	        				quotaObj.setTotalQuota(new Long(quotaUpdates.quota_set.get("gigabytes_"+vpoolName)));    			
	        			if(quotaUpdates.quota_set.containsKey("volumes_"+vpoolName))
	        				quotaObj.setVolumesLimit(new Long(quotaUpdates.quota_set.get("volumes_"+vpoolName)));
	        			if(quotaUpdates.quota_set.containsKey("snapshots_"+vpoolName))
	        				quotaObj.setSnapshotsLimit(new Long(quotaUpdates.quota_set.get("snapshots_"+vpoolName)));
	        			noEntriesInDB = false;
	        			_dbClient.persistObject(quotaObj);
	        			return getQuotaDetailFormat(header, quotaUpdates);
	    			}
    			}
    			else if(!bVpoolQuotaUpdate){
    				//The user requested update of project quota. 
    				//The current db entry is a project quota entity.(because to reach here vpoolUri should be Null.
    				if(quotaUpdates.quota_set.containsKey("gigabytes"))
        				quotaObj.setTotalQuota(new Long(quotaUpdates.quota_set.get("gigabytes")));    			
        			if(quotaUpdates.quota_set.containsKey("volumes"))
        				quotaObj.setVolumesLimit(new Long(quotaUpdates.quota_set.get("volumes")));
        			if(quotaUpdates.quota_set.containsKey("snapshots"))
        				quotaObj.setSnapshotsLimit(new Long(quotaUpdates.quota_set.get("snapshots")));
        			noEntriesInDB = false;
        			_dbClient.persistObject(quotaObj);
        			return getQuotaDetailFormat(header, quotaUpdates);
    			}
    			
    		}
    	}
    	
    	if(noEntriesInDB){    	    	
    		_log.info("No entries in the QuotaOfCinder column family");
    		QuotaOfCinder objQuotaOfCinder = new QuotaOfCinder();	     		    	
    		objQuotaOfCinder.setProject(project.getId());
    		
    		if(bVpoolQuotaUpdate){    			
    			objQuotaOfCinder.setVpool(objVpool.getId());
    			_log.info("Updating Quota of Vpool");
    			if(quotaUpdates.quota_set.containsKey("gigabytes_" + vpoolName ))
	    			objQuotaOfCinder.setTotalQuota(new Long(quotaUpdates.quota_set.get("gigabytes_" + vpoolName )));
	    		else
	    			objQuotaOfCinder.setTotalQuota(DEFAULT_VOLUME_TYPE_TOTALGB_QUOTA);
	    		
	    		if(quotaUpdates.quota_set.containsKey("volumes_" + vpoolName ))
	    			objQuotaOfCinder.setVolumesLimit(new Long(quotaUpdates.quota_set.get("volumes_" + vpoolName)));
	    		else
	    			objQuotaOfCinder.setVolumesLimit(DEFAULT_VOLUME_TYPE_VOLUMES_QUOTA);
	    		
	    		if(quotaUpdates.quota_set.containsKey("snapshots_" + vpoolName))
	    			objQuotaOfCinder.setSnapshotsLimit(new Long(quotaUpdates.quota_set.get("snapshots_" + vpoolName)));
	    		else
	    			objQuotaOfCinder.setSnapshotsLimit(DEFAULT_VOLUME_TYPE_SNAPSHOTS_QUOTA);
    			
    		}
    		else{
	    		if(quotaUpdates.quota_set.containsKey("gigabytes"))
	    			objQuotaOfCinder.setTotalQuota(new Long(quotaUpdates.quota_set.get("gigabytes")));
	    		else
	    			objQuotaOfCinder.setTotalQuota(maxQuota);
	    		
	    		if(quotaUpdates.quota_set.containsKey("volumes"))
	    			objQuotaOfCinder.setVolumesLimit(new Long(quotaUpdates.quota_set.get("volumes")));
	    		else
	    			objQuotaOfCinder.setVolumesLimit(DEFAULT_PROJECT_VOLUMES_QUOTA);
	    		
	    		if(quotaUpdates.quota_set.containsKey("snapshots"))
	    			objQuotaOfCinder.setSnapshotsLimit(new Long(quotaUpdates.quota_set.get("snapshots")));
	    		else
	    			objQuotaOfCinder.setSnapshotsLimit(DEFAULT_PROJECT_SNAPSHOTS_QUOTA);
    		}
    		objQuotaOfCinder.setId(URI.create(UUID.randomUUID().toString()));    		
			_dbClient.createObject(objQuotaOfCinder);
			_dbClient.persistObject(objQuotaOfCinder);
			return getQuotaDetailFormat(header, quotaUpdates);
    	}
    	return getQuotaDetailFormat(header, quotaUpdates);
    	
    }
    
    //internal function
    private Response getQuotaDetailFormat(HttpHeaders header, CinderQuotaDetails respCinderQuota){
    	if (CinderApiUtils.getMediaType(header).equals("xml")) {
			return CinderApiUtils.getCinderResponse(CinderApiUtils
					.convertMapToXML(respCinderQuota.quota_set, "quota_set"),
					header, false);
		} else if (CinderApiUtils.getMediaType(header).equals("json")) {
			return CinderApiUtils.getCinderResponse(respCinderQuota, header, false);
		} else {
			return Response.status(415).entity("Unsupported Media Type")
					.build();
		}
    	
    }
    
    
       
   
    private boolean isVpoolQuotaUpdate(Map<String, String> updateMap){
    	for(String iter : updateMap.keySet()){
    		if(iter.startsWith("volumes_") || iter.startsWith("snapshots_") || iter.startsWith("gigabytes_") ){
    			return true;
    		}
    	}    	
    	return false;
    }
    
    
    private String getVpoolName(Map<String, String> updateMap){
    	for(String iter : updateMap.keySet()){
    		if(iter.startsWith("volumes_") || iter.startsWith("snapshots_") || iter.startsWith("gigabytes_") ){
    			String[] splits = iter.split("_",  2);               
    	        return splits[1];
    		}
    	}    	
    	return null;
    }
    
    
    @Override
    protected URI getTenantOwner(URI id) {
    	QuotaOfCinder objQuota = (QuotaOfCinder) queryResource(id);
    	Project objProj = _dbClient.queryObject(Project.class, objQuota.getProject());
        return objProj.getTenantOrg().getURI();
    }

    /**
     * Quota is not a zone level resource
     */
    @Override
    protected boolean isZoneLevelResource() {
        return false;
    }

    @Override
    protected ResourceTypeEnum getResourceType(){
        return ResourceTypeEnum.VOLUME;
    }

    @Override
    public String getServiceType() {
        return EVENT_SERVICE_TYPE;
    }


    /**
     * Get object specific permissions filter
     *
     */
    @Override
    protected ResRepFilter<? extends RelatedResourceRep> getPermissionFilter(StorageOSUser user,
                                                                             PermissionsHelper permissionsHelper)
    {
        return new ProjOwnedResRepFilter(user, permissionsHelper, Volume.class);
    }

    @Override
    protected DataObject queryResource(URI id) {
    	QuotaOfCinder objQuotaOfCinder = _dbClient.queryObject(QuotaOfCinder.class , id);
    	return objQuotaOfCinder;
    }

}
