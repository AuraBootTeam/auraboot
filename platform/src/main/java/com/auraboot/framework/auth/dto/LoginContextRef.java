package com.auraboot.framework.auth.dto;

import lombok.Data;

@Data
public class LoginContextRef {
    private Long applicationId;
    private Long loginChannelId;
}
