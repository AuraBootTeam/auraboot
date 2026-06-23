package com.auraboot.framework.rbac.dto;

import lombok.Data;

import java.util.List;

@Data
public class AssignRolesByPidRequest {
    private String memberPid;
    private List<String> rolePids;
}
