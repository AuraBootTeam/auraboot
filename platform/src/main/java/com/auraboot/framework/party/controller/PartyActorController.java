package com.auraboot.framework.party.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.party.dto.ActorSwitchRequest;
import com.auraboot.framework.party.dto.ActorSwitchResponse;
import com.auraboot.framework.party.dto.CreatePartyRequest;
import com.auraboot.framework.party.dto.CreatePartyResponse;
import com.auraboot.framework.party.dto.PartyActorOption;
import com.auraboot.framework.party.service.PartyActorService;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/actors")
@RequiredArgsConstructor
public class PartyActorController {
    private final PartyActorService partyActorService;

    @GetMapping
    public ApiResponse<List<PartyActorOption>> listActors() {
        return ApiResponse.success(partyActorService.listActors());
    }

    @PostMapping("/parties")
    public ApiResponse<CreatePartyResponse> createParty(@Valid @RequestBody CreatePartyRequest request) {
        return ApiResponse.success(partyActorService.createParty(request));
    }

    @PostMapping("/parties/{partyId}/approve")
    @RequirePermission(MetaPermission.PARTY_MANAGE)
    public ApiResponse<Void> approveParty(@PathVariable Long partyId) {
        partyActorService.approveParty(partyId);
        return ApiResponse.success(null);
    }

    @PostMapping("/switch")
    public ApiResponse<ActorSwitchResponse> switchActor(
            @Valid @RequestBody ActorSwitchRequest request,
            @RequestHeader("Authorization") String authorization,
            HttpServletRequest httpRequest) {
        String currentToken = authorization.startsWith("Bearer ")
                ? authorization.substring(7)
                : authorization;
        return ApiResponse.success(partyActorService.switchActor(
                request.partyId(),
                currentToken,
                resolveIp(httpRequest),
                httpRequest.getHeader("User-Agent")));
    }

    private String resolveIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
