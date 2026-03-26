package com.auraboot.framework.tenant.controller;

import com.auraboot.framework.application.annotation.CurrentUserId;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.tenant.dao.entity.Invitation;
import com.auraboot.framework.tenant.service.TenantInviteService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/tenant/invite-code")
public class TenantInviteController {

    @Autowired
    private TenantInviteService tenantInviteService;

    @PostMapping("/generate")
    @ResponseBody
    public ApiResponse<String> generateInviteCode(
            @CurrentUserId Long userId,
            @RequestParam(required = true) Integer expiryDays) {
        

        String   inviteCode = tenantInviteService.generateInviteCode(userId, expiryDays);

        return ApiResponse.success(inviteCode);
    }

    @GetMapping("/validate")
    @ResponseBody
    public ApiResponse<Boolean> validateInviteCode(@RequestParam String code) {

            boolean valid = tenantInviteService.validateInviteCode(code);
            return ApiResponse.success(valid);

    }

    @PostMapping("/use")
    @ResponseBody
    public ApiResponse<Boolean> useInviteCode(
            @RequestParam String code,
            @CurrentUserId Long userId) {

            boolean result = tenantInviteService.useInviteCode(code, userId);
            return ApiResponse.success(result);


    }

    @GetMapping("/current")
    @ResponseBody
    public ApiResponse<Map<String, Object>> getCurrentInviteCode(@CurrentUserId Long userId) {

            Invitation invitation = tenantInviteService.getCurrentValidInviteCode(userId);
            if (invitation != null) {
                Map<String, Object> result = new HashMap<>();
                result.put("code", invitation.getInviteCode());
                result.put("expiredAt", invitation.getExpiredAt());
                result.put("createdAt", invitation.getCreatedAt());
                return ApiResponse.success(result);
            } else {
                return ApiResponse.success(null);
            }

    }
    
    @PostMapping("/revoke")
    @ResponseBody
    public ApiResponse<Boolean> revokeInviteCode(
            @RequestParam String code,
            @CurrentUserId Long userId) {

           boolean result = tenantInviteService.revokeInviteCode(userId, code);
           return ApiResponse.success(result);


    }
}
