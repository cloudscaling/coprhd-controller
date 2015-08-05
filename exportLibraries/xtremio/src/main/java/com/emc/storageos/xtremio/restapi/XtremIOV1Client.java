package com.emc.storageos.xtremio.restapi;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.svcs.errorhandling.resources.InternalException;
import com.emc.storageos.xtremio.restapi.XtremIOConstants.XTREMIO_TAG_ENTITY;
import com.emc.storageos.xtremio.restapi.errorhandling.XtremIOApiException;
import com.emc.storageos.xtremio.restapi.model.XtremIOResponseContent;
import com.emc.storageos.xtremio.restapi.model.request.XtremIOFolderCreate;
import com.emc.storageos.xtremio.restapi.model.request.XtremIOInitiatorCreate;
import com.emc.storageos.xtremio.restapi.model.request.XtremIOInitiatorGroupCreate;
import com.emc.storageos.xtremio.restapi.model.request.XtremIOInitiatorGroupFolderCreate;
import com.emc.storageos.xtremio.restapi.model.request.XtremIOLunMapCreate;
import com.emc.storageos.xtremio.restapi.model.request.XtremIOV1SnapCreate;
import com.emc.storageos.xtremio.restapi.model.request.XtremIOVolumeCreate;
import com.emc.storageos.xtremio.restapi.model.request.XtremIOVolumeExpand;
import com.emc.storageos.xtremio.restapi.model.request.XtremIOVolumeFolderCreate;
import com.emc.storageos.xtremio.restapi.model.response.XtremIOCGResponse;
import com.emc.storageos.xtremio.restapi.model.response.XtremIOCluster;
import com.emc.storageos.xtremio.restapi.model.response.XtremIOClusterInfo;
import com.emc.storageos.xtremio.restapi.model.response.XtremIOClusters;
import com.emc.storageos.xtremio.restapi.model.response.XtremIOConsistencyGroup;
import com.emc.storageos.xtremio.restapi.model.response.XtremIOFolders;
import com.emc.storageos.xtremio.restapi.model.response.XtremIOIGFolder;
import com.emc.storageos.xtremio.restapi.model.response.XtremIOIGFolderResponse;
import com.emc.storageos.xtremio.restapi.model.response.XtremIOInitiator;
import com.emc.storageos.xtremio.restapi.model.response.XtremIOInitiatorGroup;
import com.emc.storageos.xtremio.restapi.model.response.XtremIOInitiatorGroups;
import com.emc.storageos.xtremio.restapi.model.response.XtremIOInitiatorInfo;
import com.emc.storageos.xtremio.restapi.model.response.XtremIOInitiators;
import com.emc.storageos.xtremio.restapi.model.response.XtremIOInitiatorsInfo;
import com.emc.storageos.xtremio.restapi.model.response.XtremIOObjectInfo;
import com.emc.storageos.xtremio.restapi.model.response.XtremIOPort;
import com.emc.storageos.xtremio.restapi.model.response.XtremIOPortInfo;
import com.emc.storageos.xtremio.restapi.model.response.XtremIOPorts;
import com.emc.storageos.xtremio.restapi.model.response.XtremIOPortsInfo;
import com.emc.storageos.xtremio.restapi.model.response.XtremIOResponse;
import com.emc.storageos.xtremio.restapi.model.response.XtremIOSystem;
import com.emc.storageos.xtremio.restapi.model.response.XtremIOVolume;
import com.emc.storageos.xtremio.restapi.model.response.XtremIOVolumeInfo;
import com.emc.storageos.xtremio.restapi.model.response.XtremIOVolumes;
import com.emc.storageos.xtremio.restapi.model.response.XtremIOVolumesInfo;
import com.emc.storageos.xtremio.restapi.model.response.XtremIOXMS;
import com.emc.storageos.xtremio.restapi.model.response.XtremIOXMSResponse;
import com.emc.storageos.xtremio.restapi.model.response.XtremIOXMSsInfo;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;

public class XtremIOV1Client extends XtremIOClient {
    
    private static Logger log = LoggerFactory.getLogger(XtremIOV1Client.class);

    public XtremIOV1Client(URI baseURI, String username, String password, Client client) {
        super(baseURI, username, password, client);
    }
    
    public List<XtremIOVolume> getXtremIOVolumesForLinks(List<XtremIOVolumeInfo> volumeLinks, String clusterName) throws Exception {
        List<XtremIOVolume> volumeList = new ArrayList<XtremIOVolume>();
        for (XtremIOVolumeInfo volumeInfo : volumeLinks) {
            log.debug("Trying to get volume details for {}", volumeInfo.getHref());
            try {
                URI volumeURI = URI.create(volumeInfo.getHref());
                ClientResponse response = get(volumeURI);
                XtremIOVolumes volumes = getResponseObject(XtremIOVolumes.class, response);
                log.info("Volume {}", volumes.getContent().getVolInfo().get(1) + "-"
                        + volumes.getContent().getVolInfo().get(2));
                volumeList.add(volumes.getContent());
            } catch (InternalException ex) {
                log.warn("Exception while trying to retrieve xtremio volume link {}", volumeInfo.getHref());
            }
        }

        return volumeList;
    }

    @Override
    public List<XtremIOSystem> getXtremIOSystemInfo() throws Exception {
        ClientResponse response = get(XtremIOConstants.XTREMIO_BASE_CLUSTERS_URI);
        log.info(response.toString());
        XtremIOClusters xioClusters = getResponseObject(XtremIOClusters.class, response);
        log.info("Returned Clusters : {}", xioClusters.getClusters().length);
        List<XtremIOSystem> discoveredXIOSystems = new ArrayList<XtremIOSystem>();
        for (XtremIOCluster cluster : xioClusters.getClusters()) {
            URI clusterURI = URI.create(cluster.getHref());
            response = get(clusterURI);
            XtremIOClusterInfo xioSystem = getResponseObject(XtremIOClusterInfo.class, response);
            log.info("System {}", xioSystem.getContent().getName() + "-"
                    + xioSystem.getContent().getSerialNumber() + "-"
                    + xioSystem.getContent().getVersion());
            discoveredXIOSystems.add(xioSystem.getContent());
        }
        return discoveredXIOSystems;
    }

    @Override
    public List<XtremIOPort> getXtremIOPortInfo(String clusterName) throws Exception {
        ClientResponse response = get(XtremIOConstants.XTREMIO_TARGETS_URI);
        XtremIOPortsInfo targetPortLinks = getResponseObject(XtremIOPortsInfo.class, response);
        log.info("Returned Target Links size : {}", targetPortLinks.getPortInfo().length);
        List<XtremIOPort> targetPortList = new ArrayList<XtremIOPort>();
        for (XtremIOPortInfo targetPortInfo : targetPortLinks.getPortInfo()) {
            URI targetPortUri = URI.create(targetPortInfo.getHref());
            response = get(targetPortUri);
            XtremIOPorts targetPorts = getResponseObject(XtremIOPorts.class, response);
            log.info("Target Port {}", targetPorts.getContent().getName() + "-"
                    + targetPorts.getContent().getPortAddress());
            targetPortList.add(targetPorts.getContent());
        }
        return targetPortList;
    }

    @Override
    public List<XtremIOInitiator> getXtremIOInitiatorsInfo(String clusterName) throws Exception {
        ClientResponse response = get(XtremIOConstants.XTREMIO_INITIATORS_URI);
        XtremIOInitiatorsInfo initiatorPortLinks = getResponseObject(XtremIOInitiatorsInfo.class,
                response);
        log.info("Returned Initiator Links size : {}", initiatorPortLinks.getInitiators().length);
        List<XtremIOInitiator> initiatorPortList = new ArrayList<XtremIOInitiator>();
        for (XtremIOInitiatorInfo initiatorPortInfo : initiatorPortLinks.getInitiators()) {
            URI initiatorPortUri = URI.create(initiatorPortInfo.getHref());
            response = get(initiatorPortUri);
            XtremIOInitiators initiatorPorts = getResponseObject(XtremIOInitiators.class, response);
            log.info("Initiator Port {}", initiatorPorts.getContent().getName() + "-"
                    + initiatorPorts.getContent().getPortAddress());
            initiatorPortList.add(initiatorPorts.getContent());
        }
        return initiatorPortList;
    }

    @Override
    public List<XtremIOVolume> getXtremIOVolumes(String clusterName) throws Exception {
        ClientResponse response = get(XtremIOConstants.XTREMIO_VOLUMES_URI);
        XtremIOVolumesInfo volumeLinks = getResponseObject(XtremIOVolumesInfo.class, response);
        log.info("Returned Volume Links size : {}", volumeLinks.getVolumeInfo().length);
        List<XtremIOVolume> volumeList = getXtremIOVolumesForLinks(Arrays.asList(volumeLinks.getVolumeInfo()), clusterName);

        return volumeList;
    }

    @Override
    public List<XtremIOVolumeInfo> getXtremIOVolumeLinks(String clusterName) throws Exception {
        ClientResponse response = get(XtremIOConstants.XTREMIO_VOLUMES_URI);
        XtremIOVolumesInfo volumeLinks = getResponseObject(XtremIOVolumesInfo.class, response);

        return Arrays.asList(volumeLinks.getVolumeInfo());
    }

    @Override
    public void createVolumeFolder(String projectName, String parentFolder) throws Exception {
        try {
            XtremIOVolumeFolderCreate volumeFolderCreate = new XtremIOVolumeFolderCreate();
            volumeFolderCreate.setCaption(projectName);
            volumeFolderCreate.setParentFolderId(parentFolder);
            ClientResponse response = post(XtremIOConstants.XTREMIO_VOLUME_FOLDERS_URI,
                    getJsonForEntity(volumeFolderCreate));
            getResponseObject(XtremIOFolderCreate.class, response);
        } catch (Exception e) {
            // TODO Right now making the fix very simple ,instead of trying to acquire a lock on Storage System
            if (null != e.getMessage() && !e.getMessage().contains(XtremIOConstants.CAPTION_NOT_UNIQUE)) {
                throw e;
            } else {
                log.warn("Volume folder {} already created by a different operation at the same time", projectName);
            }
        }
        
    }

    @Override
    public void createTag(String tagName, XTREMIO_TAG_ENTITY entityType, String clusterName) throws Exception {
        throw XtremIOApiException.exceptions.operationNotSupportedForVersion("createTag");
    }

    @Override
    public void createInitiatorGroupFolder(String igFolderName, String clusterName) throws Exception {
        try {
            XtremIOInitiatorGroupFolderCreate igFolderCreate = new XtremIOInitiatorGroupFolderCreate();
            igFolderCreate.setCaption(igFolderName);
            igFolderCreate.setParentFolderId("/");
            ClientResponse response = post(XtremIOConstants.XTREMIO_INITIATOR_GROUPS_FOLDER_URI,
                    getJsonForEntity(igFolderCreate));
        } catch (Exception e) {
            log.warn("Initiator Group Folder  {} already available", igFolderName);
        }
        
    }

    @Override
    public void deleteInitiatorGroupFolder(String igFolderName, String clusterName) throws Exception {
        String uriStr = XtremIOConstants.XTREMIO_INITIATOR_GROUPS_FOLDER_STR.concat(XtremIOConstants.getInputNameString(igFolderName));
        log.info("Calling Delete on uri : {}", uriStr);
        delete(URI.create(uriStr));
    }

    @Override
    public void deleteVolumeFolder(String folderName, String clusterName) throws Exception {
        String uriStr = XtremIOConstants.XTREMIO_VOLUME_FOLDERS_STR.concat(XtremIOConstants.getInputNameString(folderName));
        log.info("Calling Delete on uri : {}", uriStr);
        delete(URI.create(uriStr));
    }

    @Override
    public List<String> getVolumeFolderNames() throws Exception {
        List<String> folderNames = new ArrayList<String>();
        ClientResponse response = get(XtremIOConstants.XTREMIO_VOLUME_FOLDERS_URI);
        XtremIOFolderCreate responseObjs = getResponseObject(XtremIOFolderCreate.class, response);
        for (XtremIOResponseContent responseObj : responseObjs.getVolumeFolders()) {
            folderNames.add(responseObj.getName());
        }
        return folderNames;
    }

    @Override
    public List<String> getTagNames(String clusterName) throws Exception {
        throw XtremIOApiException.exceptions.operationNotSupportedForVersion("getTagNames");
    }

    @Override
    public int getNumberOfVolumesInFolder(String folderName, String clusterName) throws Exception {
        String uriStr = XtremIOConstants.XTREMIO_VOLUME_FOLDERS_STR.concat(XtremIOConstants.getInputNameString(folderName));
        log.info("Calling Get on uri : {}", uriStr);
        ClientResponse response = get(URI.create(uriStr));
        XtremIOFolders folderResponse = getResponseObject(XtremIOFolders.class, response);
        log.info("{} volumes present in folder {}", folderResponse.getContent()
                .getNumberOfVolumes(), folderName);
        return Integer.parseInt(folderResponse.getContent().getNumberOfVolumes());
    }

    @Override
    public int getNumberOfInitiatorsInInitiatorGroup(String igName, String clusterName) throws Exception {
        String uriStr = XtremIOConstants.XTREMIO_INITIATOR_GROUPS_STR.concat(XtremIOConstants.getInputNameString(igName));
        log.info("Calling Get on uri : {}", uriStr);
        ClientResponse response = get(URI.create(uriStr));
        XtremIOInitiatorGroups igResponse = getResponseObject(XtremIOInitiatorGroups.class,
                response);
        log.info("{} initaitors present in ig Group {}", igResponse.getContent()
                .getNumberOfInitiators(), igName);
        return Integer.parseInt(igResponse.getContent().getNumberOfInitiators());
    }

    @Override
    public int getNumberOfVolumesInInitiatorGroup(String igName, String clusterName) throws Exception {
        String uriStr = XtremIOConstants.XTREMIO_INITIATOR_GROUPS_STR.concat(XtremIOConstants.getInputNameString(igName));
        log.info("Calling Get on uri : {}", uriStr);
        ClientResponse response = get(URI.create(uriStr));
        XtremIOInitiatorGroups igResponse = getResponseObject(XtremIOInitiatorGroups.class,
                response);
        log.info("{} volumes present in ig Group {}", igResponse.getContent().getNumberOfVolumes(),
                igName);
        return Integer.parseInt(igResponse.getContent().getNumberOfVolumes());
    }

    @Override
    public XtremIOResponse createVolume(String volumeName, String size, String parentFolderName, String clusterName) throws Exception {
        XtremIOVolumeCreate volCreate = new XtremIOVolumeCreate();
        volCreate.setName(volumeName);
        volCreate.setSize(size);
        volCreate.setParentFolderId(parentFolderName);
        log.info("Calling Volume Create with: {}", volCreate.toString());

        ClientResponse response = post(XtremIOConstants.XTREMIO_VOLUMES_URI,
                getJsonForEntity(volCreate));
        return getResponseObject(XtremIOResponse.class, response);
    }

    @Override
    public XtremIOResponse createVolumeSnapshot(String parentVolumeName, String snapName, String folderName, String snapType, String clusterName) throws Exception {
        XtremIOV1SnapCreate snapCreate = new XtremIOV1SnapCreate();
        snapCreate.setParentName(parentVolumeName);
        snapCreate.setSnapName(snapName);
        snapCreate.setFolderId(folderName);
        log.info("Calling Snapshot Create with URI: {} and paramaters: {}", XtremIOConstants.XTREMIO_SNAPS_URI.toString(), snapCreate.toString());

        ClientResponse response = post(XtremIOConstants.XTREMIO_SNAPS_URI,
                getJsonForEntity(snapCreate));
        return getResponseObject(XtremIOResponse.class, response);
    }

    @Override
    public XtremIOResponse createConsistencyGroupSnapshot(String consistencyGroupName, String snapshotSetName, String folderName,
            String snapType, String clusterName) throws Exception {
        throw XtremIOApiException.exceptions.operationNotSupportedForVersion("createConsistencyGroupSnapshot");
    }

    @Override
    public void deleteSnapshot(String snapName, String clusterName) throws Exception {
        String uriStr = XtremIOConstants.XTREMIO_SNAPS_STR.concat(XtremIOConstants.getInputNameString(snapName));
        log.info("Calling Delete on uri : {}", uriStr);
        delete(URI.create(uriStr));
    }

    @Override
    public void expandVolume(String volumeName, String size, String clusterName) throws Exception {
        XtremIOVolumeExpand volExpand = new XtremIOVolumeExpand();
        volExpand.setSize(size);
        log.info("Calling Volume Expand with: {}", volExpand.toString());
        String volUriStr = XtremIOConstants.XTREMIO_VOLUMES_STR.concat(XtremIOConstants.getInputNameString(volumeName));
        put(URI.create(volUriStr), getJsonForEntity(volExpand));
    }

    @Override
    public XtremIOResponse createInitiator(String initiatorName, String igId, String portAddress, String clusterName) throws Exception {
        XtremIOInitiatorCreate initiatorCreate = new XtremIOInitiatorCreate();
        initiatorCreate.setInitiatorGroup(igId);
        initiatorCreate.setName(initiatorName);
        initiatorCreate.setPortAddress(portAddress);

        log.info("Calling Initiator Create with: {}", initiatorCreate.toString());

        ClientResponse response = post(XtremIOConstants.XTREMIO_INITIATORS_URI,
                getJsonForEntity(initiatorCreate));
        return getResponseObject(XtremIOResponse.class, response);
    }

    @Override
    public void createInitiatorGroup(String igName, String parentFolderId, String clusterName) throws Exception {
        try {
            XtremIOInitiatorGroupCreate initiatorGroupCreate = new XtremIOInitiatorGroupCreate();
            initiatorGroupCreate.setName(igName);
            initiatorGroupCreate.setParentFolderId(parentFolderId);
            post(XtremIOConstants.XTREMIO_INITIATOR_GROUPS_URI,
                    getJsonForEntity(initiatorGroupCreate));
        } catch (Exception e) {
            log.warn("Initiator Group {} already available", igName);
        }
    }

    @Override
    public void createLunMap(String volName, String igName, String hlu, String clusterName) throws Exception {
        XtremIOLunMapCreate lunMapCreate = new XtremIOLunMapCreate();
        if (!hlu.equalsIgnoreCase("-1")) {
            lunMapCreate.setHlu(hlu);
        }
        lunMapCreate.setInitiatorGroupName(igName);
        lunMapCreate.setName(volName);
        log.info("Calling lun map Create {}", lunMapCreate.toString());
        try {
            post(XtremIOConstants.XTREMIO_LUNMAPS_URI, getJsonForEntity(lunMapCreate));
        } catch (Exception e) {
            // TODO Right now making the fix very simple ,instead of trying to acquire a lock on Storage System
            if (null != e.getMessage() && !e.getMessage().contains(XtremIOConstants.VOLUME_MAPPED)) {
                throw e;
            } else {
                log.warn("Volume  {} already mapped to IG {}", volName, igName);
            }
        }
    }

    @Override
    public XtremIOInitiator getInitiator(String initiatorName, String clusterName) throws Exception {
        try {
            String uriStr = XtremIOConstants.XTREMIO_INITIATORS_STR.concat(XtremIOConstants.getInputNameString(initiatorName));
            log.info("Calling Get Initiator with  uri : {}", uriStr);
            ClientResponse response = get(URI.create(uriStr));
            XtremIOInitiators initiators = getResponseObject(XtremIOInitiators.class, response);
            return initiators.getContent();
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        log.info("Initiators not registered on Array with name : {}", initiatorName);
        return null;
    }

    @Override
    public XtremIOInitiatorGroup getInitiatorGroup(String initiatorGroupName, String clusterName) throws Exception {
        try {
            String uriStr = XtremIOConstants.XTREMIO_INITIATOR_GROUPS_STR.concat(XtremIOConstants.getInputNameString(initiatorGroupName));
            log.info("Calling Get Initiator Group with with uri : {}", uriStr);
            ClientResponse response = get(URI.create(uriStr));
            XtremIOInitiatorGroups igGroups = getResponseObject(XtremIOInitiatorGroups.class,
                    response);
            return igGroups.getContent();
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        log.info("Initiator Group not registered on Array with name : {}", initiatorGroupName);
        return null;
    }

    @Override
    public XtremIOIGFolder getInitiatorGroupFolder(String initiatorGroupFolderName, String clusterName) throws Exception {
        try {
            String uriStr = XtremIOConstants.XTREMIO_INITIATOR_GROUPS_FOLDER_STR.concat(
                    XtremIOConstants.getInputNameString(initiatorGroupFolderName));
            log.info("Calling Get Initiator Group Folder with with uri : {}", uriStr);
            ClientResponse response = get(URI.create(uriStr));
            XtremIOIGFolderResponse folderResponse = getResponseObject(
                    XtremIOIGFolderResponse.class, response);
            return folderResponse.getContent();

        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        log.info("Initiator Group Folder not available on Array with name : {}",
                initiatorGroupFolderName);
        return null;
    }

    @Override
    public void deleteInitiatorGroup(String igName, String clusterName) throws Exception {
        String uriStr = XtremIOConstants.XTREMIO_INITIATOR_GROUPS_STR.concat(XtremIOConstants.getInputNameString(igName));
        log.info("Calling Delete Initiator Group with uri : {}", uriStr);
        delete(URI.create(uriStr));
    }

    @Override
    public XtremIOVolume getVolumeDetails(String volumeName, String clusterName) throws Exception {
        String uriStr = XtremIOConstants.XTREMIO_VOLUMES_STR.concat(XtremIOConstants.getInputNameString(volumeName));
        log.info("Calling Get on Volume URI : {}", uriStr);
        ClientResponse response = get(URI.create(uriStr));
        XtremIOVolumes volumesResponse = getResponseObject(XtremIOVolumes.class, response);
        return volumesResponse.getContent();
    }

    @Override
    public XtremIOVolume getSnapShotDetails(String snapName, String clusterName) throws Exception {
        String uriStr = XtremIOConstants.XTREMIO_SNAPS_STR.concat(XtremIOConstants.getInputNameString(snapName));
        log.info("Calling Get on Snapshot URI : {}", uriStr);
        ClientResponse response = get(URI.create(uriStr));
        XtremIOVolumes volumesResponse = getResponseObject(XtremIOVolumes.class, response);
        return volumesResponse.getContent();
    }

    @Override
    public XtremIOConsistencyGroup getConsistencyGroupDetails(String cgName, String clusterName) throws Exception {
        throw XtremIOApiException.exceptions.operationNotSupportedForVersion("getConsistencyGroupDetails");
    }

    @Override
    public void deleteVolume(String volumeName, String clusterName) throws Exception {
        String uriStr = XtremIOConstants.XTREMIO_VOLUMES_STR.concat(XtremIOConstants.getInputNameString(volumeName));
        log.info("Volume Delete URI : {}", uriStr);
        delete(URI.create(uriStr));
    }

    @Override
    public void deleteInitiator(String initiatorName, String clusterName) throws Exception {
        String uriStr = XtremIOConstants.XTREMIO_INITIATORS_STR.concat(XtremIOConstants.getInputNameString(initiatorName));
        log.info("Initiator Delete URI : {}", uriStr);
        delete(URI.create(uriStr));
    }

    @Override
    public void deleteLunMap(String lunMap, String clusterName) throws Exception {
        String uriStr = XtremIOConstants.XTREMIO_LUNMAPS_STR.concat(XtremIOConstants.getInputNameString(lunMap));
        log.info("Calling Delete on LunMap URI : {}", uriStr);
        delete(URI.create(uriStr));
    }

    @Override
    public XtremIOResponse createConsistencyGroup(String cgName, String clusterName) throws Exception {
        throw XtremIOApiException.exceptions.operationNotSupportedForVersion("createConsistencyGroup");
    }

    @Override
    public void removeConsistencyGroup(String cgName, String clusterName) throws Exception {
        throw XtremIOApiException.exceptions.operationNotSupportedForVersion("removeConsistencyGroup");
    }

    @Override
    public XtremIOResponse addVolumeToConsistencyGroup(String volName, String cgName, String clusterName) throws Exception {
        throw XtremIOApiException.exceptions.operationNotSupportedForVersion("addVolumeToConsistencyGroup");
    }

    @Override
    public void removeVolumeFromConsistencyGroup(String volName, String cgName, String clusterName) throws Exception {
        throw XtremIOApiException.exceptions.operationNotSupportedForVersion("removeVolumeFromConsistencyGroup");
    }

    @Override
    public void deleteSnapshotSet(String snapshotSetName, String clusterName) throws Exception {
        throw XtremIOApiException.exceptions.operationNotSupportedForVersion("deleteSnapshotSet");
    }

    @Override
    public XtremIOResponse restoreVolumeFromSnapshot(String clusterName, String volName, String snapshotName) throws Exception {
        throw XtremIOApiException.exceptions.operationNotSupportedForVersion("restoreVolumeFromSnapshot");
    }

    @Override
    public XtremIOResponse refreshSnapshotFromVolume(String clusterName, String volName, String snapshotName) throws Exception {
        throw XtremIOApiException.exceptions.operationNotSupportedForVersion("refreshSnapshotFromVolume");
    }

    @Override
    public XtremIOResponse restoreCGFromSnapshot(String clusterName, String cgName, String snapshotName) throws Exception {
        throw XtremIOApiException.exceptions.operationNotSupportedForVersion("restoreCGFromSnapshot");
    }

    @Override
    public XtremIOResponse refreshSnapshotFromCG(String clusterName, String cgName, String snapshotName) throws Exception {
        throw XtremIOApiException.exceptions.operationNotSupportedForVersion("refreshSnapshotFromCG");
    }

    @Override
    public XtremIOXMS getXtremIOXMSInfo() throws Exception {
        ClientResponse response = get(XtremIOConstants.XTREMIO_XMS_URI);
        XtremIOXMSsInfo xmssInfo = getResponseObject(XtremIOXMSsInfo.class, response);
        for(XtremIOObjectInfo xmsInfo : xmssInfo.getXmssInfo()) {
            URI xmsURI = URI.create(xmsInfo.getHref());
            response = get(xmsURI);
            XtremIOXMSResponse xmsResponse = getResponseObject(XtremIOXMSResponse.class, response);
            return xmsResponse.getContent();
        }
        return null;
    }

    @Override
    public XtremIOSystem getClusterDetails(String clusterSerialNumber) throws Exception {
        List<XtremIOSystem> clusters = getXtremIOSystemInfo();
        if(!clusters.isEmpty()) {
            return clusters.get(0);
        }
        
        return null;
    }
}
