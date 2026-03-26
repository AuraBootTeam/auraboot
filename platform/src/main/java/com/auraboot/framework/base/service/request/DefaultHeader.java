package com.auraboot.framework.base.service.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DefaultHeader implements Header {

    private String userPid;

    private String action;

    private String id;

    private String version;

    private String token;

}
