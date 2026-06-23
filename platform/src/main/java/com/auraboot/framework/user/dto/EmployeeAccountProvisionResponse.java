package com.auraboot.framework.user.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Result of a customer employee account batch provision.
 */
@Data
@Builder
public class EmployeeAccountProvisionResponse {
    private int total;
    private List<Account> accounts;

    @Data
    @Builder
    public static class Account {
        private Long userId;
        private String userPid;
        private String name;
        private String type;
        private String userName;
        private String email;
        private String initialPassword;
        private List<String> assignedRoles;
        private boolean mustChangePassword;
    }
}
