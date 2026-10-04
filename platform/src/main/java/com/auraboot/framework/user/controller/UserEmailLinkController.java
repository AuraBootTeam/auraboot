package com.auraboot.framework.user.controller;

import com.auraboot.framework.application.annotation.CurrentUserId;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.AuthenticatedAccess;
import com.auraboot.framework.permission.annotation.DisallowImpersonation;
import com.auraboot.framework.user.dto.EmailLinkConfirmRequest;
import com.auraboot.framework.user.dto.EmailLinkSendCodeRequest;
import com.auraboot.framework.user.dto.EmailLinkStatusResponse;
import com.auraboot.framework.user.service.UserEmailLinkService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user/email-link")
@RequiredArgsConstructor
@AuthenticatedAccess("operates only on the caller's own verified email credential")
@DisallowImpersonation
public class UserEmailLinkController {

    private final UserEmailLinkService userEmailLinkService;

    @GetMapping
    public ApiResponse<EmailLinkStatusResponse> status(@CurrentUserId Long userId) {
        return ApiResponse.success(userEmailLinkService.status(userId));
    }

    @PostMapping("/send-code")
    public ApiResponse<Void> sendCode(
            @CurrentUserId Long userId,
            @Valid @RequestBody EmailLinkSendCodeRequest request,
            HttpServletRequest httpRequest) {
        userEmailLinkService.sendCode(userId, request.email(), httpRequest.getRemoteAddr());
        return ApiResponse.success(null);
    }

    @PostMapping("/confirm")
    public ApiResponse<EmailLinkStatusResponse> confirm(
            @CurrentUserId Long userId,
            @Valid @RequestBody EmailLinkConfirmRequest request) {
        return ApiResponse.success(userEmailLinkService.confirm(userId, request.email(), request.code()));
    }
}
