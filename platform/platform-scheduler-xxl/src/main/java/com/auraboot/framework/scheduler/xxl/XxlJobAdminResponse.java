package com.auraboot.framework.scheduler.xxl;

import lombok.Data;

@Data
public class XxlJobAdminResponse {

    private boolean success;
    private String externalJobId;
    private String message;
}
