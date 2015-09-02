package com.emc.storageos.vplexcontroller;

import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.db.client.DbClient;
import com.emc.storageos.db.client.constraint.AlternateIdConstraint;
import com.emc.storageos.db.client.constraint.URIQueryResultList;
import com.emc.storageos.db.client.model.BlockObject;
import com.emc.storageos.db.client.model.DataObject;
import com.emc.storageos.db.client.model.Project;
import com.emc.storageos.db.client.model.StorageSystem;
import com.emc.storageos.db.client.model.StringMap;
import com.emc.storageos.db.client.model.StringSet;
import com.emc.storageos.db.client.model.StringSetMap;
import com.emc.storageos.db.client.model.UnManagedDiscoveredObjects.UnManagedVolume;
import com.emc.storageos.db.client.model.UnManagedDiscoveredObjects.UnManagedVolume.SupportedVolumeCharacterstics;
import com.emc.storageos.db.client.model.UnManagedDiscoveredObjects.UnManagedVolume.SupportedVolumeInformation;
import com.emc.storageos.vplex.api.VPlexApiConstants;
import com.emc.storageos.vplex.api.VPlexApiException;
import com.emc.storageos.vplex.api.VPlexDeviceInfo;
import com.emc.storageos.vplex.api.VPlexDistributedDeviceInfo;
import com.emc.storageos.vplex.api.VPlexResourceInfo;
import com.emc.storageos.vplex.api.VPlexStorageVolumeInfo;

public class VplexBackendIngestionContext {

    public static final String UNMANAGEDVOLUME = "UNMANAGEDVOLUME";
    public static final String VOLUME = "VOLUME";
    public static final String DISCOVERY_MODE = "controller_vplex_volume_discovery_mode";
    public static final String DISCOVERY_MODE_DISCOVERY_ONLY = "Only During Discovery";
    public static final String DISCOVERY_MODE_INGESTION_ONLY = "Only During Ingestion";
    public static final String DISCOVERY_MODE_HYBRID = "During Discovery and Ingestion";
    public static final String DISCOVERY_FILTER = "controller_vplex_volume_discovery_filter";
    public static final String DISCOVERY_KILL_SWITCH = "controller_vplex_volume_discovery_kill_switch";
    
    private static Logger _logger = LoggerFactory.getLogger(VplexBackendIngestionContext.class);
    private DbClient _dbClient;
    private UnManagedVolume _unmanagedVirtualVolume;

    private boolean _discoveryInProgress = false;
    private boolean _ingestionInProgress = false;
    private boolean _inDiscoveryOnlyMode = false;
    private boolean _shouldCheckForMirrors = false;
    
    private VPlexResourceInfo topLevelDevice;
    private List<UnManagedVolume> unmanagedBackendVolumes;
    private List<UnManagedVolume> unmanagedSnapshots;
    private Map<UnManagedVolume, UnManagedVolume> unmanagedFullClones;
    private Map<UnManagedVolume, Set<UnManagedVolume>> unmanagedBackendOnlyClones;
    private Map<UnManagedVolume, String> unmanagedMirrors;
    private Map<String, Map<String, VPlexDeviceInfo>> mirrorMap;
    private Map<String, VPlexStorageVolumeInfo> backendVolumeWwnToInfoMap ;
    
    private Project backendProject;
    private Project frontendProject;
    
    private Map<String, UnManagedVolume> processedUnManagedVolumeMap = new HashMap<String, UnManagedVolume>();
    private Map<String, BlockObject> createdObjectMap = new HashMap<String, BlockObject>();
    private Map<String, List<DataObject>> updatedObjectMap = new HashMap<String, List<DataObject>>();
    private List<BlockObject> ingestedObjects = new ArrayList<BlockObject>();
    
    private BackendDiscoveryPerformanceTracker _tracker;
    
    public VplexBackendIngestionContext(UnManagedVolume unManagedVolume, DbClient dbClient) {
        this._unmanagedVirtualVolume = unManagedVolume;
        this._dbClient = dbClient;
        this._tracker = new BackendDiscoveryPerformanceTracker();
    }

    public void discover() {
        this.setDiscoveryInProgress(true);
        this.getUnmanagedBackendVolumes();
        this.getUnmanagedFullClones();
        this.getUnmanagedMirrors();
    }
    
    public UnManagedVolume getUnmanagedVirtualVolume() {
        return this._unmanagedVirtualVolume;
    }
    
    public List<UnManagedVolume> getAllUnmanagedVolumes() {
        List<UnManagedVolume> allVolumes = new ArrayList<UnManagedVolume>();
        allVolumes.addAll(getUnmanagedBackendVolumes());
        allVolumes.addAll(getUnmanagedSnapshots());
        // allVolumes.addAll(getUnmanagedFullClones().keySet()); only need vvols
        allVolumes.addAll(getUnmanagedFullClones().values());
        for (Set<UnManagedVolume> backendClones : getUnmanagedBackendOnlyClones().values()) {
            allVolumes.addAll(backendClones);
        }
        allVolumes.addAll(getUnmanagedMirrors().keySet());
        _logger.info("collected all unmanaged volumes: " + allVolumes);
        return allVolumes;
    }
    
    public List<UnManagedVolume> getUnmanagedBackendVolumes() {
        if (null != unmanagedBackendVolumes) {
            return unmanagedBackendVolumes;
        }
        
        if (!isDiscoveryInProgress()) {
            // first check the database for this unmanaged volume's backend volumes
            StringSet dbBackendVolumes = extractValuesFromStringSet(
                    SupportedVolumeInformation.VPLEX_BACKEND_VOLUMES.toString(),
                    _unmanagedVirtualVolume.getVolumeInformation());
            if (null != dbBackendVolumes && !dbBackendVolumes.isEmpty()) {
                List<URI> umvUris = new ArrayList<URI>();
                for (String nativeId : dbBackendVolumes) {
                    _logger.info("\tfound unmanaged backend volume native id " + nativeId);
                    URIQueryResultList unManagedVolumeList = new URIQueryResultList();
                    _dbClient.queryByConstraint(AlternateIdConstraint.Factory
                            .getVolumeInfoNativeIdConstraint(nativeId), unManagedVolumeList);
                    if (unManagedVolumeList.iterator().hasNext()) {
                        umvUris.add(unManagedVolumeList.iterator().next());
                    }
                }
                if (!umvUris.isEmpty()) {
                    boolean isLocal = isVplexLocal();
                    if ((isLocal && umvUris.size() == 1) || (!isLocal && umvUris.size() == 2)) {
                        // only return vols from the database if we have the correct number
                        // of backend volumes for this type of unmanaged vplex virtual volume
                        unmanagedBackendVolumes = _dbClient.queryObject(UnManagedVolume.class, umvUris, true);
                        _logger.info("\treturning unmanaged backend volume objects: " + unmanagedBackendVolumes);
                        return unmanagedBackendVolumes;
                    }
                }
            }
        }
        
        // if they couldn't be found in the database,
        // we will query the VPLEX API for this information
        long start = System.currentTimeMillis();
        _logger.info("getting unmanaged backend volumes");
        unmanagedBackendVolumes = new ArrayList<UnManagedVolume>();
        
        // if we're in discovery only mode, don't check again during ingestion
        if (isIngestionInProgress() && isInDiscoveryOnlyMode()) {
            return unmanagedBackendVolumes;
        }
        
        List<URI> associatedVolumeUris = new ArrayList<URI>();
        
        if (!getBackendVolumeWwnToInfoMap().isEmpty()) {
            for (String backendWwn : getBackendVolumeWwnToInfoMap().keySet()) {
                _logger.info("attempting to find unmanaged backend volume by wwn {}", 
                        backendWwn);
                
                URIQueryResultList results = new URIQueryResultList();
                _dbClient.queryByConstraint(AlternateIdConstraint.
                        Factory.getUnmanagedVolumeWwnConstraint(
                                BlockObject.normalizeWWN(backendWwn)), results);
                if (results.iterator() != null) {
                    for (URI uri : results) {
                        _logger.info("found backend volume " + uri);
                        associatedVolumeUris.add(uri);
                    }
                }
            }
        }
        
        unmanagedBackendVolumes = _dbClient.queryObject(UnManagedVolume.class, associatedVolumeUris);
        _logger.info("for VPLEX UnManagedVolume {}, found these associated volumes: " + unmanagedBackendVolumes, _unmanagedVirtualVolume.getLabel());
        updateUnmanagedBackendVolumesInParent();
        
        _tracker.fetchBackendVolumes = System.currentTimeMillis() - start;
        
        return unmanagedBackendVolumes;
    }
    
    private void updateUnmanagedBackendVolumesInParent() {
        if (!getUnmanagedBackendVolumes().isEmpty()) {
            StringSet bvols = new StringSet();
            for (UnManagedVolume backendVol : unmanagedBackendVolumes) {
                bvols.add(backendVol.getNativeGuid());
                StringSet parentVol = new StringSet();
                parentVol.add(_unmanagedVirtualVolume.getNativeGuid());
                backendVol.putVolumeInfo(SupportedVolumeInformation.VPLEX_PARENT_VOLUME.name(), parentVol);
                _dbClient.persistObject(backendVol);
            }
            if (bvols != null && !bvols.isEmpty()) {
                _logger.info("setting VPLEX_BACKEND_VOLUMES: " + unmanagedBackendVolumes);
                _unmanagedVirtualVolume.putVolumeInfo(SupportedVolumeInformation.VPLEX_BACKEND_VOLUMES.name(), bvols);
            }
        } else {
            _logger.warn("no backend volumes were found for {}, have the backend storage arrays already been discovered?", _unmanagedVirtualVolume.getLabel());
        }
    }
    
    private Map<String, VPlexStorageVolumeInfo> getBackendVolumeWwnToInfoMap() {
        if (null != backendVolumeWwnToInfoMap) {
            return backendVolumeWwnToInfoMap;
        }
        
        _logger.info("getting backend volume wwn to api info map");
        boolean success = false;
        try {
            // first trying with a null mirror map to save some time
            backendVolumeWwnToInfoMap = 
                    VPlexControllerUtils.getStorageVolumeInfoForDevice(
                            getSupportingDeviceName(), getLocality(), getClusterName(), null,
                            _unmanagedVirtualVolume.getStorageSystemUri(), _dbClient);
            success = true;
        } catch (VPlexApiException ex) {
            _logger.warn("failed to find wwn to storage volume map on "
                    + "first try with no mirror map, will analyze mirrors and try again", ex);
            _shouldCheckForMirrors = true;
        }
        
        if (!success) {
            try {
                // first trying with a null mirror map to save some time
                backendVolumeWwnToInfoMap = 
                        VPlexControllerUtils.getStorageVolumeInfoForDevice(
                                getSupportingDeviceName(), getLocality(), getClusterName(), getMirrorMap(),
                                _unmanagedVirtualVolume.getStorageSystemUri(), _dbClient);
            } catch (VPlexApiException ex) {
                _logger.error("could not determine backend storage volumes for {}: {}", 
                        _unmanagedVirtualVolume.getLabel(), ex.getLocalizedMessage());
                // TODO: exception
                throw new RuntimeException(
                        getSupportingDeviceName() + ": " +
                        ex.getLocalizedMessage());
            }
        }
        
        _logger.info("backend volume wwn to api info map: " + backendVolumeWwnToInfoMap);
        return backendVolumeWwnToInfoMap;
    }
    
    public List<String> getBackendVolumeGuids() {

        List<String> associatedVolumeGuids = new ArrayList<String>();

        for (UnManagedVolume vol : this.getUnmanagedBackendVolumes()) {
            associatedVolumeGuids.add(vol.getNativeGuid().replace(UNMANAGEDVOLUME, VOLUME));
        }
        
        _logger.info("associated volume guids: " + associatedVolumeGuids);
        return associatedVolumeGuids;
    }

    public List<UnManagedVolume> getUnmanagedSnapshots() {
        if (null != unmanagedSnapshots) {
            return unmanagedSnapshots;
        }

        long start = System.currentTimeMillis();
        _logger.info("getting unmanaged snapshots");
        unmanagedSnapshots = new ArrayList<UnManagedVolume>();
        
        for (UnManagedVolume sourceVolume : this.getUnmanagedBackendVolumes()) {
            unmanagedSnapshots.addAll(getUnManagedSnaphots(sourceVolume));
        }
        
        _logger.info("found these associated snapshots: " + unmanagedSnapshots);
        _tracker.fetchSnapshots = System.currentTimeMillis() - start;

        return unmanagedSnapshots;
    }

    // map of parent backend volume to children clone/copy backend volumes
    public Map<UnManagedVolume, Set<UnManagedVolume>> getUnmanagedBackendOnlyClones() {
        if (null != unmanagedBackendOnlyClones) {
            return unmanagedBackendOnlyClones;
        }
        
        long start = System.currentTimeMillis();
        _logger.info("getting unmanaged backend-only clones");
        unmanagedBackendOnlyClones = new HashMap<UnManagedVolume, Set<UnManagedVolume>>();
        
        for (UnManagedVolume sourceVolume : getUnmanagedBackendVolumes()) {
            Set<UnManagedVolume> backendClonesFound = new HashSet<UnManagedVolume>();
            backendClonesFound.addAll(getUnManagedClones(sourceVolume));
            if (!backendClonesFound.isEmpty()) {
                for (UnManagedVolume foundClone : backendClonesFound) {
                    boolean addIt = true;
                    if (!getUnmanagedFullClones().isEmpty()) {
                        for (UnManagedVolume knownClone : getUnmanagedFullClones().keySet()) {
                            if (knownClone.getId().toString().equals(foundClone.getId().toString())) {
                                _logger.info("clone {} is already part of a full clone, "
                                        + "excluding it from backend-only clones", knownClone.getLabel());
                                addIt = false;
                            }
                        }
                    }
                    if (addIt) {
                        unmanagedBackendOnlyClones.put(sourceVolume, backendClonesFound);
                    }
                }
            }
        }
        
        _logger.info("unmanaged backend-only clones found: " + unmanagedBackendOnlyClones);
        _tracker.fetchBackendOnlyClones = System.currentTimeMillis() - start;

        return unmanagedBackendOnlyClones;
    }
    
    // map of backend clone volume to virtual volume 
    public Map<UnManagedVolume, UnManagedVolume> getUnmanagedFullClones() {
        if (null != unmanagedFullClones) {
            return unmanagedFullClones;
        }

        if (!isDiscoveryInProgress()) {
            // first check the database for this unmanaged volume's backend full clones
            StringSet fullCloneMap = extractValuesFromStringSet(
                    SupportedVolumeInformation.VPLEX_FULL_CLONE_MAP.toString(),
                    _unmanagedVirtualVolume.getVolumeInformation());
            if (null != fullCloneMap && !fullCloneMap.isEmpty()) {
                for (String fullCloneEntry : fullCloneMap) {
                    String[] pair = fullCloneEntry.split("=");
                    UnManagedVolume backendClone = null;
                    UnManagedVolume vvolClone = null;
                    URIQueryResultList unManagedVolumeList = new URIQueryResultList();
                    _dbClient.queryByConstraint(AlternateIdConstraint.Factory
                            .getVolumeInfoNativeIdConstraint(pair[0]), unManagedVolumeList);
                    if (unManagedVolumeList.iterator().hasNext()) {
                        backendClone = _dbClient.queryObject(UnManagedVolume.class, 
                                unManagedVolumeList.iterator().next());
                    }
                    unManagedVolumeList = new URIQueryResultList();
                    _dbClient.queryByConstraint(AlternateIdConstraint.Factory
                            .getVolumeInfoNativeIdConstraint(pair[1]), unManagedVolumeList);
                    if (unManagedVolumeList.iterator().hasNext()) {
                        vvolClone = _dbClient.queryObject(UnManagedVolume.class, 
                                unManagedVolumeList.iterator().next());
                    }
                    if (null == unmanagedFullClones) {
                        unmanagedFullClones = new HashMap<UnManagedVolume, UnManagedVolume>();
                    }
                    unmanagedFullClones.put(backendClone, vvolClone);
                }
                if (null != unmanagedFullClones && !unmanagedFullClones.isEmpty()) {
                    return unmanagedFullClones;
                }
            }
        }
        
        // if they couldn't be found in the database,
        // we will query the VPLEX API for this information
        long start = System.currentTimeMillis();
        _logger.info("getting unmanaged full clones");
        unmanagedFullClones = new HashMap<UnManagedVolume, UnManagedVolume>();
        
        // if we're in discovery only mode, don't check again during ingestion
        if (isIngestionInProgress() && isInDiscoveryOnlyMode()) {
            return unmanagedFullClones;
        }
        
        List<UnManagedVolume> backendClonesFound = new ArrayList<UnManagedVolume>();
        for (UnManagedVolume sourceVolume : getUnmanagedBackendVolumes()) {
            backendClonesFound.addAll(getUnManagedClones(sourceVolume));
        }
        
        if (backendClonesFound.isEmpty()) {
            _logger.info("no clones found for source volumes: " + getUnmanagedBackendVolumes());
        } else {
            
            Map<String, URI> deviceToUnManagedVolumeMap = getVplexDeviceToUnManagedVolumeMap();

            
            for (UnManagedVolume backendClone : backendClonesFound) {
                String volumeNativeId = extractValueFromStringSet(
                        SupportedVolumeInformation.NATIVE_ID.toString(),
                        backendClone.getVolumeInformation());
                
                StorageSystem backendSystem = 
                        _dbClient.queryObject(StorageSystem.class, backendClone.getStorageSystemUri());
                
                String deviceName = VPlexControllerUtils.getDeviceForStorageVolume(
                        volumeNativeId, backendClone.getWwn(), backendSystem.getSerialNumber(), 
                        _unmanagedVirtualVolume.getStorageSystemUri(), _dbClient);
                
                if (null != deviceName) {
                    _logger.info("found device name {} for native id {}", deviceName, volumeNativeId);
                    URI umvUri = deviceToUnManagedVolumeMap.get(deviceName);
                    if (null != umvUri) {
                        UnManagedVolume virtualVolumeClone = _dbClient.queryObject(UnManagedVolume.class, umvUri);
                        if (null != virtualVolumeClone) {
                            _logger.info("adding mapping for vvol clone {} to backend clone {}", 
                                    virtualVolumeClone, backendClone);
                            unmanagedFullClones.put(backendClone, virtualVolumeClone);
                            _logger.info("   because this clone has a virtual volume "
                                    + "in front of it, removing from backend only clone set");
                            Iterator<Entry<UnManagedVolume, Set<UnManagedVolume>>> it = 
                                    getUnmanagedBackendOnlyClones().entrySet().iterator();
                            while (it.hasNext()) {
                               Entry<UnManagedVolume, Set<UnManagedVolume>> item = it.next();
                               if (item.getKey().getId().toString().equals(backendClone.getId().toString())){
                                   getUnmanagedBackendOnlyClones().remove(item.getKey());
                               }
                            }
                        }
                    }
                } else {
                    _logger.info("could not determine supporting device name for native id " + volumeNativeId);
                }
            }
        }
        
        _logger.info("unmanaged full clones found: " + unmanagedFullClones);
        _tracker.fetchFullClones = System.currentTimeMillis() - start;
        if (!unmanagedFullClones.isEmpty()) {
            StringSet cloneEntries = new StringSet();
            for (Entry<UnManagedVolume,UnManagedVolume> cloneEntry : unmanagedFullClones.entrySet()) {
                cloneEntries.add(cloneEntry.getKey().getNativeGuid() + "=" + cloneEntry.getValue().getNativeGuid());
            }
            if (cloneEntries != null && !cloneEntries.isEmpty()) {
                _logger.info("setting VPLEX_FULL_CLONE_MAP: " + cloneEntries);
                _unmanagedVirtualVolume.putVolumeInfo(SupportedVolumeInformation.VPLEX_FULL_CLONE_MAP.name(), cloneEntries);
            }
        }
        
        return unmanagedFullClones;
    }

    // map of unmanaged volume to vplex device info context path
    public Map<UnManagedVolume, String> getUnmanagedMirrors() {
        if (null != unmanagedMirrors) {
            return unmanagedMirrors;
        }

        if (!isDiscoveryInProgress()) {
            // first check the database for this unmanaged volume's backend mirrors
            StringSet mirrorMap = extractValuesFromStringSet(
                    SupportedVolumeInformation.VPLEX_MIRROR_MAP.toString(),
                    _unmanagedVirtualVolume.getVolumeInformation());
            if (null != mirrorMap && !mirrorMap.isEmpty()) {
                for (String mirrorEntry : mirrorMap) {
                    String[] pair = mirrorEntry.split("=");
                    UnManagedVolume mirrorVolume = null;
                    String contextPath = pair[1];
                    URIQueryResultList unManagedVolumeList = new URIQueryResultList();
                    _dbClient.queryByConstraint(AlternateIdConstraint.Factory
                            .getVolumeInfoNativeIdConstraint(pair[0]), unManagedVolumeList);
                    if (unManagedVolumeList.iterator().hasNext()) {
                        mirrorVolume = _dbClient.queryObject(UnManagedVolume.class, 
                                unManagedVolumeList.iterator().next());
                    }
                    if (null != mirrorVolume && null != contextPath) {
                        if (null == unmanagedMirrors) {
                            unmanagedMirrors = new HashMap<UnManagedVolume, String>();
                        }
                        unmanagedMirrors.put(mirrorVolume, contextPath);
                        Iterator<UnManagedVolume> itr = getUnmanagedBackendVolumes().iterator();
                        while (itr.hasNext()) {
                            if (mirrorVolume.getId().toString().equals(itr.next().getId().toString())) {
                                itr.remove();
                            }
                        }
                    }
                }
                if (null != unmanagedMirrors && !unmanagedMirrors.isEmpty()) {
                    return unmanagedMirrors;
                }
            }
        }

        unmanagedMirrors = new HashMap<UnManagedVolume, String>();
        
        // if a simple 1-1-1 device structure was found, no need
        // to check for native mirrors
        if (!_shouldCheckForMirrors) {
            return unmanagedMirrors;
        }
        
        // if we're in discovery only mode, don't check again during ingestion
        if (isIngestionInProgress() && isInDiscoveryOnlyMode()) {
            return unmanagedMirrors;
        }

        // if they couldn't be found in the database,
        // we will query the VPLEX API for this information
        long start = System.currentTimeMillis();
        _logger.info("getting unmanaged mirrors");
        if (!getMirrorMap().isEmpty()) {
            for (Entry<String, Map<String, VPlexDeviceInfo>> mirrorMapEntry : getMirrorMap().entrySet()) {
                _logger.info("looking at mirrors for device leg on cluster " + mirrorMapEntry.getKey());
                Map<String, VPlexDeviceInfo> slotToDeviceMap = mirrorMapEntry.getValue();
                if (null != slotToDeviceMap && !slotToDeviceMap.isEmpty()) {
                    UnManagedVolume associatedVolumeSource = null; 
                    UnManagedVolume associatedVolumeMirror = null; 
                    for (Entry<String, VPlexDeviceInfo> entry : slotToDeviceMap.entrySet()) {
                        // TODO extract constants
                        if ("0".equals(entry.getKey())) {
                            _logger.info("looking at slot-0");
                            associatedVolumeSource = getAssociatedVolumeForComponentDevice(entry.getValue());
                        }
                        if ("1".equals(entry.getKey())) {
                            _logger.info("looking at slot-1");
                            associatedVolumeMirror = getAssociatedVolumeForComponentDevice(entry.getValue());
                        }
                    }
                    if (null != associatedVolumeMirror && null != associatedVolumeSource) {
                        _logger.info("removing mirror volume {} from associated "
                                + "vols and adding to mirrors", associatedVolumeMirror.getLabel());
                        getUnmanagedBackendVolumes().remove(associatedVolumeMirror);
                        _logger.info("getUnmanagedBackendVolumes() is now: " + getUnmanagedBackendVolumes());
                        unmanagedMirrors.put(associatedVolumeMirror, slotToDeviceMap.get("1").getPath());
                        StringSet set = new StringSet();
                        set.add(associatedVolumeMirror.getNativeGuid());
                        _logger.info("adding mirror set {} to source unmanaged volume {}", set, associatedVolumeSource);
                        associatedVolumeSource.putVolumeInfo(SupportedVolumeInformation.MIRRORS.toString(), set);
                        associatedVolumeSource.putVolumeInfo(SupportedVolumeInformation.VPLEX_NATIVE_MIRROR_TARGET_VOLUME.toString(), set);
                        set = new StringSet();
                        set.add(associatedVolumeSource.getNativeGuid());
                        associatedVolumeMirror.putVolumeInfo(SupportedVolumeInformation.VPLEX_NATIVE_MIRROR_SOURCE_VOLUME.toString(), set);
                        // need to go ahead and persist any changes to backend volume info
                        _dbClient.persistObject(associatedVolumeSource);
                        _dbClient.persistObject(associatedVolumeMirror);
                    } else {
                        // TODO: create exception
                        String message = "couldn't find all associated device components in mirror device: ";
                        message += " associatedVolumeSource is " + associatedVolumeSource;
                        message += " and associatedVolumeMirror is " +  associatedVolumeMirror;
                        throw new RuntimeException(message);
                    }
                }
            }
        }

        _logger.info("unmanaged mirrors found: " + unmanagedMirrors);
        _tracker.fetchMirrors = System.currentTimeMillis() - start;
        if (!unmanagedMirrors.isEmpty()) {
            
            StringSet mirrorEntries = new StringSet();
            for (Entry<UnManagedVolume,String> mirrorEntry : unmanagedMirrors.entrySet()) {
                mirrorEntries.add(mirrorEntry.getKey().getNativeGuid() + "=" + mirrorEntry.getValue());
            }
            if (mirrorEntries != null && !mirrorEntries.isEmpty()) {
                _logger.info("setting VPLEX_MIRROR_MAP: " + mirrorEntries);
                _unmanagedVirtualVolume.putVolumeInfo(SupportedVolumeInformation.VPLEX_MIRROR_MAP.name(), mirrorEntries);
            }
            // need to update the backend volumes because a mirror target shouldn't be considered a direct backend volume
            updateUnmanagedBackendVolumesInParent();
        }
        return unmanagedMirrors;
    }

    private UnManagedVolume getAssociatedVolumeForComponentDevice( VPlexDeviceInfo device ) {
        String devicePath = device.getPath();
        _logger.info("associated volume device context path: " + devicePath);
        for (Entry<String, VPlexStorageVolumeInfo> entry : getBackendVolumeWwnToInfoMap().entrySet()) {
            String storageVolumePath = entry.getValue().getPath();
            _logger.info("\tstorage volume context path: " + storageVolumePath);
            // context paths should overlap if the device contains the storage volume...
            if (null != storageVolumePath && storageVolumePath.startsWith(devicePath)) {
                _logger.info("\t\tthis storage volume is a match, trying to find unmanaged backend volume");
                for (UnManagedVolume vol : getUnmanagedBackendVolumes()) {
                    _logger.info("\t\t\tlooking at " + vol.getNativeGuid());
                    if (vol.getWwn().equalsIgnoreCase(entry.getKey())) {
                        _logger.info("\t\t\t\tit's a match for " + vol.getWwn());
                        return vol;
                    }
                }
            }
        }
        
        return null;
    }
    
    // map of cluster id to sorted map of slot number to VPlexDeviceInfo 
    private Map<String, Map<String, VPlexDeviceInfo>> getMirrorMap() {
        if (null != mirrorMap) {
            return mirrorMap;
        }
        
        _logger.info("assembling mirror map");
        mirrorMap = new HashMap<String, Map<String, VPlexDeviceInfo>>();
        VPlexResourceInfo device = getTopLevelDevice();
        if (isLocal() && (device instanceof VPlexDeviceInfo)) {
            VPlexDeviceInfo localDevice = ((VPlexDeviceInfo) device);
            if (VPlexApiConstants.ARG_GEOMETRY_RAID1.equals(
                    ((VPlexDeviceInfo) device).getGeometry())) {
                mirrorMap.put(localDevice.getCluster(), mapDevices(localDevice));
            }
        } else {
            for (VPlexDeviceInfo localDevice : 
                ((VPlexDistributedDeviceInfo) device).getLocalDeviceInfo()) {
                if (VPlexApiConstants.ARG_GEOMETRY_RAID1.equals(
                        ((VPlexDistributedDeviceInfo) device).getGeometry())) {
                    mirrorMap.put(localDevice.getCluster(), mapDevices(localDevice));
                }
            }
        }
        
        _logger.info("mirror map is: " + mirrorMap);
        return mirrorMap;
    }
    
    private Map<String, VPlexDeviceInfo> mapDevices( VPlexDeviceInfo parentDevice ) {
        _logger.info("   mapping device " + parentDevice.getName());
        Map<String, VPlexDeviceInfo> mirrorDevices = new TreeMap<String, VPlexDeviceInfo>();
        for (VPlexDeviceInfo dev : parentDevice.getChildDeviceInfo()) {
            mirrorDevices.put(dev.getSlotNumber(), dev);
        }
        return mirrorDevices;
    }

    public VPlexResourceInfo getTopLevelDevice() {
        if (null != topLevelDevice) {
            return topLevelDevice;
        }
        
        long start = System.currentTimeMillis();
        _logger.info("getting top level device");
        topLevelDevice = VPlexControllerUtils.getSupportingDeviceInfo(
                getSupportingDeviceName(), getLocality(), 
                getUnmanagedVirtualVolume().getStorageSystemUri(), 
                _dbClient);
        
        _logger.info("top level device is: " + topLevelDevice);
        _tracker.fetchTopLevelDevice = System.currentTimeMillis() - start;
        return topLevelDevice;
    }

    public String getSupportingDeviceName() {
        String deviceName = extractValueFromStringSet(
                SupportedVolumeInformation.VPLEX_SUPPORTING_DEVICE_NAME.toString(),
                _unmanagedVirtualVolume.getVolumeInformation());
        return deviceName;
    }
    
    public String getLocality() {
        String locality = extractValueFromStringSet(
                SupportedVolumeInformation.VPLEX_LOCALITY.toString(),
                _unmanagedVirtualVolume.getVolumeInformation());
        return locality;
    }
    
    public String getClusterName() {
        String cluster = extractValueFromStringSet(
                SupportedVolumeInformation.VPLEX_CLUSTER_IDS.toString(),
                _unmanagedVirtualVolume.getVolumeInformation());
        // even if both clusters are listed, returning just one is fine
        return cluster;
    }
    
    public boolean isLocal() {
        return VPlexApiConstants.LOCAL_VIRTUAL_VOLUME.equals(getLocality());
    }

    /**
     * @return the processedUnManagedVolumeMap
     */
    public Map<String, UnManagedVolume> getProcessedUnManagedVolumeMap() {
        return processedUnManagedVolumeMap;
    }

    /**
     * @return the createdObjectMap
     */
    public Map<String, BlockObject> getCreatedObjectMap() {
        return createdObjectMap;
    }

    /**
     * @return the updatedObjectMap
     */
    public Map<String, List<DataObject>> getUpdatedObjectMap() {
        return updatedObjectMap;
    }

    /**
     * @return the ingestedObjects
     */
    public List<BlockObject> getIngestedObjects() {
        return ingestedObjects;
    }
    
    public String toStringDebug() {
        StringBuilder s = new StringBuilder("\n\nVplexBackendIngestionContext \n\t ");
        s.append("unmanaged virtual volume: ").append(this._unmanagedVirtualVolume).append(" \n\t ");
        s.append("unmanaged backend volume(s): ").append(this.getUnmanagedBackendVolumes()).append(" \n\t ");
        s.append("unmanaged snapshots: ").append(this.getUnmanagedSnapshots()).append(" \n\t ");
        s.append("unmanaged full clones: ").append(this.getUnmanagedFullClones()).append(" \n\t ");
        s.append("unmanaged backend only clones: ").append(this.getUnmanagedBackendOnlyClones()).append(" \n\t ");
        s.append("unmanaged mirrors: ").append(this.getUnmanagedMirrors()).append(" \n\t ");
        s.append("ingested objects: ").append(this.getIngestedObjects()).append(" \n\t ");
        s.append("created objects map: ").append(this.getCreatedObjectMap()).append(" \n\t ");
        s.append("updated objects map: ").append("\n");
        for (Entry<String, List<DataObject>> e : this.getUpdatedObjectMap().entrySet()) {
            s.append(e.getKey()).append(": ");
            for (DataObject o : e.getValue()) {
                s.append(" \n\t\t ").append(o.getLabel());
            }
            s.append("\n\t");
        }
        s.append("processed unmanaged volumes: ").append(this.getProcessedUnManagedVolumeMap()).append("\n");
        return s.toString();
    }
    
    /**
     * @return the backendProject
     */
    public Project getBackendProject() {
        return backendProject;
    }

    /**
     * @param backendProject the backendProject to set
     */
    public void setBackendProject(Project backendProject) {
        this.backendProject = backendProject;
    }

    /**
     * @return the frontendProject
     */
    public Project getFrontendProject() {
        return frontendProject;
    }

    /**
     * @param frontendProject the frontendProject to set
     */
    public void setFrontendProject(Project frontendProject) {
        this.frontendProject = frontendProject;
    }
    
    /**
     * @return true if in discovery mode (gets everything fresh)
     */
    public boolean isDiscoveryInProgress() {
        return _discoveryInProgress;
    }

    /**
     * @param set the discovery mode
     */
    public void setDiscoveryInProgress(boolean inDiscoveryInProgress) {
        this._discoveryInProgress = inDiscoveryInProgress;
    }
    
    /**
     * @return the inDiscoveryOnlyMode
     */
    public boolean isInDiscoveryOnlyMode() {
        return _inDiscoveryOnlyMode;
    }

    /**
     * @param inDiscoveryOnlyMode the inDiscoveryOnlyMode to set
     */
    public void setInDiscoveryOnlyMode(boolean inDiscoveryOnlyMode) {
        this._inDiscoveryOnlyMode = inDiscoveryOnlyMode;
    }
    
    /**
     * @return the ingestionInProgress
     */
    public boolean isIngestionInProgress() {
        return _ingestionInProgress;
    }

    /**
     * @param ingestionInProgress the ingestionInProgress to set
     */
    public void setIngestionInProgress(boolean ingestionInProgress) {
        this._ingestionInProgress = ingestionInProgress;
    }

    /**
     * extract value from a String Set
     * This method is used, to get value from a StringSet of size 1.
     * 
     * @param key
     * @param volumeInformation
     * @return String
     */
    public static String extractValueFromStringSet(String key, StringSetMap volumeInformation) {
        try {
            StringSet availableValueSet = volumeInformation.get(key);
            if (null != availableValueSet) {
                for (String value : availableValueSet) {
                    return value;
                }
            }
        } catch (Exception e) {
            _logger.error(e.getMessage(), e);
        }
        return null;
    }
    
    /**
     * extract values from a String Set
     * This method is used, to get value from a StringSet of variable size.
     * 
     * @param key
     * @param volumeInformation
     * @return String[]
     */
    public static StringSet extractValuesFromStringSet(String key, StringSetMap volumeInformation) {
        try {
            StringSet returnSet = new StringSet();
            StringSet availableValueSet = volumeInformation.get(key);
            if (null != availableValueSet) {
                for (String value : availableValueSet) {
                    returnSet.add(value);
                }
            }
            return returnSet;
        } catch (Exception e) {
            _logger.error(e.getMessage(), e);
        }
        return null;
    }

    public List<UnManagedVolume> getUnManagedSnaphots(UnManagedVolume unManagedVolume) {
        List<UnManagedVolume> snapshots = new ArrayList<UnManagedVolume>();
        _logger.info("checking for snapshots related to unmanaged volume " + unManagedVolume.getLabel());
        if (checkUnManagedVolumeHasReplicas(unManagedVolume)) {
            StringSet snapshotNativeIds = extractValuesFromStringSet(
                    SupportedVolumeInformation.SNAPSHOTS.toString(),
                    unManagedVolume.getVolumeInformation());
            List<URI> snapshotUris = new ArrayList<URI>();
            if (null != snapshotNativeIds && !snapshotNativeIds.isEmpty()) {
                for (String nativeId : snapshotNativeIds) {
                    _logger.info("   found snapshot native id " + nativeId);
                    URIQueryResultList unManagedVolumeList = new URIQueryResultList();
                    _dbClient.queryByConstraint(AlternateIdConstraint.Factory
                            .getVolumeInfoNativeIdConstraint(nativeId), unManagedVolumeList);
                    if (unManagedVolumeList.iterator().hasNext()) {
                        snapshotUris.add(unManagedVolumeList.iterator().next());
                    }
                }
            }
            if (!snapshotUris.isEmpty()) {
                snapshots = _dbClient.queryObject(UnManagedVolume.class, snapshotUris, true);
                _logger.info("   returning snapshot objects: " + snapshots);
            }
            
        }

        return snapshots;
    }

    public List<UnManagedVolume> getUnManagedClones(UnManagedVolume unManagedVolume) {
        List<UnManagedVolume> clones = new ArrayList<UnManagedVolume>();
        _logger.info("checking for clones (full copies) related to unmanaged volume " + unManagedVolume.getLabel());
        if (checkUnManagedVolumeHasReplicas(unManagedVolume)) {
            StringSet cloneNativeIds = extractValuesFromStringSet(
                    SupportedVolumeInformation.FULL_COPIES.toString(),
                    unManagedVolume.getVolumeInformation());
            List<URI> cloneUris = new ArrayList<URI>();
            if (null != cloneNativeIds && !cloneNativeIds.isEmpty()) {
                for (String nativeId : cloneNativeIds) {
                    _logger.info("   found clone native id " + nativeId);
                    URIQueryResultList unManagedVolumeList = new URIQueryResultList();
                    _dbClient.queryByConstraint(AlternateIdConstraint.Factory
                            .getVolumeInfoNativeIdConstraint(nativeId), unManagedVolumeList);
                    if (unManagedVolumeList.iterator().hasNext()) {
                        cloneUris.add(unManagedVolumeList.iterator().next());
                    }
                }
            }
            if (!cloneUris.isEmpty()) {
                clones = _dbClient.queryObject(UnManagedVolume.class, cloneUris, true);
                _logger.info("   returning clone objects: " + clones);
            }
            
        }

        return clones;
    }

    public boolean checkUnManagedVolumeHasReplicas(UnManagedVolume unManagedVolume) {
        StringMap unManagedVolumeCharacteristics = unManagedVolume.getVolumeCharacterstics();
        String volumeHasReplicas = unManagedVolumeCharacteristics
                .get(SupportedVolumeCharacterstics.HAS_REPLICAS.toString());
        String volumeHasRemoteReplicas = unManagedVolumeCharacteristics
                .get(SupportedVolumeCharacterstics.REMOTE_MIRRORING.toString());
        if (null != volumeHasReplicas
                && Boolean.parseBoolean(volumeHasReplicas)
                || (null != volumeHasRemoteReplicas && Boolean
                        .parseBoolean(volumeHasRemoteReplicas))) {
            return true;
        }
        return false;
    }
    
    public Map<String, URI> getVplexDeviceToUnManagedVolumeMap() {
        URI vplexUri = _unmanagedVirtualVolume.getStorageSystemUri();
        Iterator<UnManagedVolume> allUnmanagedVolumes = null;
        _logger.info("about to start dingle timer");
        long dingleTimer = new Date().getTime();
        Map<String,URI> deviceToUnManagedVolumeMap = new HashMap<String,URI>();
        List<URI> storageSystem = new ArrayList<URI>();
        storageSystem.add(vplexUri);
        try {
            // TODO how long will this nonsense take? no way to query StringSetMap values with dbClient
            _logger.info("about to query for ids");
            List<URI> ids = _dbClient.queryByType(UnManagedVolume.class, true);
            List<String> fields = new ArrayList<String>();
            fields.add("storageDevice");
            fields.add("volumeInformation");
            allUnmanagedVolumes = _dbClient.queryIterativeObjectFields(UnManagedVolume.class, fields, ids);
        } catch (Throwable t) {
            // TODO: remove this, but having trouble with dbclient not logging errors in testing
            _logger.error("Throwable caught!!!!!!!!!!!!!! ");
            _logger.error("Throwable caught: " + t.toString());
        }
        if (null != allUnmanagedVolumes) {
            while (allUnmanagedVolumes.hasNext()) {
                try {
                    UnManagedVolume vol = allUnmanagedVolumes.next();
                    if (vol.getStorageSystemUri().equals(vplexUri)) {
                        String supportingDeviceName = 
                                extractValueFromStringSet(
                                        SupportedVolumeInformation.VPLEX_SUPPORTING_DEVICE_NAME.toString(), 
                                        vol.getVolumeInformation());
                        if (null != supportingDeviceName) {
                            deviceToUnManagedVolumeMap.put(supportingDeviceName, vol.getId());
                        }
                    }
                } catch (NoSuchElementException ex) {
                    _logger.warn("for some reason the database returned nonsense: " 
                                + ex.getLocalizedMessage());
                }
            }
        } else {
            // :(
            // TODO create exception
            throw new RuntimeException("could not load deviceToUnManagedVolumeMap");
        }
        _logger.info("creating deviceToUnManagedVolumeMap took {} ms", new Date().getTime() - dingleTimer);
        return deviceToUnManagedVolumeMap;
    }

    public boolean isVplexLocal() {
        String locality = extractValueFromStringSet(
                SupportedVolumeInformation.VPLEX_LOCALITY.toString(),
                    _unmanagedVirtualVolume.getVolumeInformation());
        if ("distributed".equals(locality)) {
            return false;
        }
        
        return true;
    }

    @Override
    public String toString() {
        // TODO: probably remove this because it's an expensive call
        // and kind of invalidates the lazy-load aspect of this object.
        // also, it has line breaks and tabs, which kind of violates
        // the spirit of toString.
        return toStringDebug();
    }

    public String getPerformanceReport() {
        return _tracker.getPerformanceReport();
    }
    
    private class BackendDiscoveryPerformanceTracker {
        
        public long startTime = new Date().getTime();
        public long fetchBackendVolumes = 0;
        public long fetchSnapshots = 0;
        public long fetchBackendOnlyClones = 0;
        public long fetchFullClones = 0;
        public long fetchMirrors = 0;
        public long fetchTopLevelDevice = 0;
        
        public String getPerformanceReport() {
            StringBuilder report = new StringBuilder("\n\nBackend Discovery Performance Report\n");
            report.append("\tvolume name: ").append(_unmanagedVirtualVolume.getLabel()).append("\n");
            report.append("\ttotal discovery time: ").append(System.currentTimeMillis() - startTime).append("ms\n");
            report.append("\tfetch backend volumes: ").append(fetchBackendVolumes).append("ms\n");
            report.append("\tfetch snapshots: ").append(fetchSnapshots).append("ms\n");
            report.append("\tfetch backend clones: ").append(fetchBackendOnlyClones).append("ms\n");
            report.append("\tfetch full clones: ").append(fetchFullClones).append("ms\n");
            report.append("\tfetch mirrors: ").append(fetchMirrors).append("ms\n");
            report.append("\tfetch top-level device: ").append(fetchTopLevelDevice).append("ms\n");

            return report.toString();
        }
    }
}