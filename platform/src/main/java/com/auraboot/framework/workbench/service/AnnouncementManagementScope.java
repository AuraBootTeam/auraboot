package com.auraboot.framework.workbench.service;

import java.util.*;

/** Current management authority evaluated against immutable publication-time classification. */
public record AnnouncementManagementScope(String type, String actorPid, Set<String> unitPids,
        Set<String> currentUserPids) {
    public AnnouncementManagementScope {
        unitPids=Set.copyOf(unitPids);
        currentUserPids=Set.copyOf(currentUserPids);
    }

    public boolean contains(String userPid, Map<String,Object> organization) {
        if("all".equals(type)) return true;
        if("self".equals(type)) return Objects.equals(actorPid,userPid);
        if(!Boolean.TRUE.equals(organization.get("captured"))
            || !"announcement.organization.v1".equals(organization.get("schema"))) return false;
        if(Set.of("dept","dept_and_sub").contains(type)) {
            Object departments=organization.get("departmentPids");
            return departments instanceof List<?> rows && rows.stream().anyMatch(unitPids::contains);
        }
        if("team".equals(type)) {
            Object teams=organization.get("teamPids");
            return teams instanceof List<?> rows && rows.stream().anyMatch(unitPids::contains);
        }
        return false;
    }
}
