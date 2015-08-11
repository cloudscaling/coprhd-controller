/*
 * Copyright (c) 2014 EMC Corporation
 * All Rights Reserved
 */
package com.emc.storageos.xtremio.restapi.model.response;

import org.codehaus.jackson.annotate.JsonProperty;
import org.codehaus.jackson.map.annotate.JsonRootName;

import com.google.gson.annotations.SerializedName;

@JsonRootName(value = "xtremio_folder")
public class XtremIOTag {
    @SerializedName("num-of-vols")
    @JsonProperty(value = "num-of-vols")
    private String numberOfVolumes;
    
    @SerializedName("num-of-direct-objs")
    @JsonProperty(value = "num-of-direct-objs")
    private String numberOfIGs;

    public String getNumberOfVolumes() {
        return numberOfVolumes;
    }

    public void setNumberOfVolumes(String numberOfVolumes) {
        this.numberOfVolumes = numberOfVolumes;
    }
    
    public String getNumberOfIGs() {
        return numberOfIGs;
    }

    public void setNumberOfIGs(String numberOfIGs) {
        this.numberOfIGs = numberOfIGs;
    }
}
