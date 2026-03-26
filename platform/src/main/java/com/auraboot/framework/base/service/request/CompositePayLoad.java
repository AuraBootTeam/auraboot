package com.auraboot.framework.base.service.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;

import java.util.HashMap;

@Data
public class CompositePayLoad implements Payload<HashMap> {

    //    @JsonDeserialize(keyAs = String.class, contentAs = Object.class)
    private HashMap data = new HashMap();

    @JsonAnySetter
    public void addField(String code, Object value) {
        data.put(code, value);
    }
}
