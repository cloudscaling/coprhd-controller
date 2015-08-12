package com.emc.storageos.api.system.vplex;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.eclipse.jetty.util.log.Log;
import org.junit.Assert;
import org.slf4j.Logger;

import com.emc.storageos.api.service.impl.resource.BlockService;
import com.emc.storageos.api.service.impl.resource.TaskService;
import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.model.DataObject;
import com.emc.storageos.db.client.util.NullColumnValueGetter;
import com.emc.storageos.db.joiner.Joiner;
import com.emc.storageos.model.TaskList;
import com.emc.storageos.model.TaskResourceRep;
import com.emc.storageos.model.block.UnManagedVolumeRestRep;
import com.emc.storageos.model.block.VolumeCreate;
import com.emc.storageos.model.block.VolumeDeleteTypeEnum;
import com.emc.storageos.model.block.VolumeIngest;
import com.emc.storageos.model.block.VolumeRestRep;
import com.emc.storageos.model.systems.StorageSystemRestRep;
import com.emc.vipr.client.AuthClient;
import com.emc.vipr.client.ClientConfig;
import com.emc.vipr.client.Task;
import com.emc.vipr.client.Tasks;
import com.emc.vipr.client.ViPRCoreClient;
import com.emc.vipr.client.exceptions.ServiceErrorException;
import com.rsa.crypto.ncm.key.t;

public class VPlexTestUtil {
	DbClient dbClient = null; 
	Logger log = null;
	ViPRCoreClient client = null;
	
	public VPlexTestUtil(ViPRCoreClient client, 	DbClient dbClient, Logger log) {
		this.client = client;
		this.dbClient = dbClient;
		this.log = log;
	}
	
	public List<URI> createVolume(String name, String size, Integer count, 
			URI vpool, URI varray, URI project, URI cg) {
		List<URI> volumes = new ArrayList<URI>();
		VolumeCreate createParam = new VolumeCreate();
		createParam.setName(name);
		createParam.setSize(size);;
		createParam.setCount(count);
		createParam.setVpool(vpool);
		createParam.setVarray(varray);
		createParam.setProject(project);
		createParam.setConsistencyGroup(cg);
		try {
			Tasks<VolumeRestRep> tasks = client.blockVolumes().create(createParam);
			for (VolumeRestRep volumeRestRep : tasks.get()) {
				log.info(String.format("Volume %s (%s) created", 
						volumeRestRep.getName(), volumeRestRep.getNativeId()));
				volumes.add(volumeRestRep.getId());
			}
			return volumes;
		} catch (ServiceErrorException ex) {
			log.error("Exception creating virtual volumes " + ex.getMessage(), ex);
			throw ex;
		}
	}
	
	public void deleteVolumes(List<URI> volumes, boolean inventoryOnly) {
		try {
		Tasks <VolumeRestRep> tasks = client.blockVolumes().deactivate(volumes, 
				(inventoryOnly ? VolumeDeleteTypeEnum.VIPR_ONLY : VolumeDeleteTypeEnum.FULL));
		for (VolumeRestRep volumeRestRep : tasks.get()) {
			log.info(String.format("Volume %s (%s) deleted", volumeRestRep.getName(), volumeRestRep.getId()));
			volumes.add(volumeRestRep.getId());
		}
		} catch (ServiceErrorException ex) {
			log.error("Exception creating deleting volumes " + ex.getMessage(), ex);
			throw ex;
		}
	}
	
	/**
	 * Discovers the storage system. If unmanaged is true, discovers the unmanaged artifacts.
	 * @param uri
	 * @param unmanaged
	 */
	public void discoverStorageSystem(URI uri, boolean unmanaged) {
		try {
			Task<StorageSystemRestRep > rep = 
					client.storageSystems().discover(uri, (unmanaged ? "UNMANAGED_VOLUMES" : "ALL"));
			log.info(String.format("Last discovery %s at %s status %s", rep.get().getNativeGuid(), 
					rep.get().getLastMeteringRunTime(), rep.get().getLastDiscoveryStatusMessage()));
		} catch (ServiceErrorException ex) {
			log.error("Exception discovering storage system " + ex.getMessage(), ex);
			throw ex;
		}
	}
	
	public void ingestUnManagedVolume(List<URI> volumes, URI project, URI varray, URI vpool) {
		try {
			VolumeIngest input = new VolumeIngest();
			input.setProject(project);;
			input.setVarray(varray);;
			input.setVpool(vpool);
			input.setUnManagedVolumes(volumes);;
			Tasks<UnManagedVolumeRestRep> rep = client.unmanagedVolumes().ingest(input);
			for (UnManagedVolumeRestRep uvol : rep.get()) {
				log.info(String.format("Unmanaged volume %s ", uvol.getNativeGuid()));
			}
		} catch (ServiceErrorException ex) {
			log.error("Exception discovering storage system " + ex.getMessage(), ex);
			throw ex;
		}
	}
	
	
	public <T extends DataObject> URI getURIFromLabel(Class<T>  clazz, String label) {
		Joiner j = new Joiner(dbClient);
		Set<URI> uris = j.join(clazz, "a").match("label", label).go().uris("a");
		if (uris.isEmpty()) {
			return null;
		} else {
			return uris.iterator().next();
		}
	}
	
	
	public<T extends DataObject> T lookupObject(Class<T> clazz, URI uri) {
		if (NullColumnValueGetter.isNullURI(uri)) {
			return null;
		}
		T object = dbClient.queryObject(clazz, uri);
		return object;
	}
	

}
