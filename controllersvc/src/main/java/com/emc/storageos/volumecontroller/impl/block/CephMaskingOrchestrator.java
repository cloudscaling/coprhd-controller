package com.emc.storageos.volumecontroller.impl.block;

import java.net.URI;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.emc.storageos.volumecontroller.BlockStorageDevice;

public class CephMaskingOrchestrator extends AbstractBasicMaskingOrchestrator {
    private static final Logger _log = LoggerFactory.getLogger(CephMaskingOrchestrator.class);

    @Override
    public BlockStorageDevice getDevice() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void exportGroupAddVolumes(URI storageURI, URI exportGroupURI, Map<URI, Integer> volumeMap, String token) throws Exception {
        // TODO Auto-generated method stub
    }

}
