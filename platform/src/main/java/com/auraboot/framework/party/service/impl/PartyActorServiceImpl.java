package com.auraboot.framework.party.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.constant.ExecutionScope;
import com.auraboot.framework.auth.constant.SessionStage;
import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.auth.dto.SessionTokenContext;
import com.auraboot.framework.auth.service.SessionManagementService;
import com.auraboot.framework.auth.util.JwtUtil;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.constant.StatusConstants;
import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.party.dto.ActorSwitchResponse;
import com.auraboot.framework.party.dto.CreatePartyRequest;
import com.auraboot.framework.party.dto.CreatePartyResponse;
import com.auraboot.framework.party.dto.PartyActorOption;
import com.auraboot.framework.party.entity.Party;
import com.auraboot.framework.party.entity.PartyLifecycleTransition;
import com.auraboot.framework.party.entity.PartyMembership;
import com.auraboot.framework.party.mapper.ActorPreferenceMapper;
import com.auraboot.framework.party.mapper.PartyLifecycleTransitionMapper;
import com.auraboot.framework.party.mapper.PartyMapper;
import com.auraboot.framework.party.mapper.PartyMemberRoleMapper;
import com.auraboot.framework.party.mapper.PartyMembershipMapper;
import com.auraboot.framework.party.service.ActorCandidateResolver;
import com.auraboot.framework.party.service.PartyActorService;
import com.auraboot.framework.saas.config.service.SystemModeService;
import com.auraboot.framework.saas.constant.PartyCreationPolicy;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class PartyActorServiceImpl implements PartyActorService {
    private final PartyMapper partyMapper;
    private final PartyMembershipMapper partyMembershipMapper;
    private final PartyMemberRoleMapper partyMemberRoleMapper;
    private final PartyLifecycleTransitionMapper lifecycleTransitionMapper;
    private final ActorPreferenceMapper actorPreferenceMapper;
    private final TenantMemberService tenantMemberService;
    private final SystemModeService systemModeService;
    private final UserService userService;
    private final JwtUtil jwtUtil;
    private final SessionManagementService sessionManagementService;
    private final ActorCandidateResolver actorCandidateResolver;

    @Override
    public List<PartyActorOption> listActors() {
        Context context = requireActiveTenantMember();
        List<PartyActorOption> options = actorCandidateResolver.resolveCandidates(
                context.tenantId(),
                context.tenantMemberId(),
                MetaContext.getCurrentApplicationId(),
                MetaContext.getCurrentLoginChannelId());
        options.forEach(option -> {
            option.setCurrent(option.getPartyId().equals(MetaContext.getCurrentActorPartyId())
                    && option.getPartyMembershipId().equals(MetaContext.getCurrentPartyMembershipId()));
            option.setPartyRoleCodes(partyMemberRoleMapper.findActiveRoleCodes(
                    context.tenantId(), option.getPartyMembershipId()));
        });
        return options;
    }

    @Override
    @Transactional
    public CreatePartyResponse createParty(CreatePartyRequest request) {
        Context context = requireActiveTenantMember();
        PartyCreationPolicy policy = systemModeService.getPartyCreationPolicy();
        if (policy == PartyCreationPolicy.DISABLED) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "Party creation is disabled");
        }

        boolean active = policy == PartyCreationPolicy.AUTO_APPROVE;
        Instant now = Instant.now();
        Party party = new Party();
        party.setPid(UlidGenerator.generate());
        party.setTenantId(context.tenantId());
        party.setCode(request.getCode().trim().toLowerCase(Locale.ROOT));
        party.setDisplayName(request.getDisplayName().trim());
        party.setLegalName(trimToNull(request.getLegalName()));
        party.setPartyType(request.getPartyType());
        party.setLifecycleStatus(active ? StatusConstants.ACTIVE : StatusConstants.PENDING);
        party.setDeletedFlag(false);
        party.setCreatedAt(now);
        party.setUpdatedAt(now);
        party.setCreatedBy(context.userId());
        party.setUpdatedBy(context.userId());
        partyMapper.insert(party);

        PartyMembership membership = new PartyMembership();
        membership.setPid(UlidGenerator.generate());
        membership.setTenantId(context.tenantId());
        membership.setPartyId(party.getId());
        membership.setTenantMemberId(context.tenantMemberId());
        membership.setStatus(active ? StatusConstants.ACTIVE : StatusConstants.PENDING);
        membership.setJoinedAt(active ? now : null);
        membership.setCreatedAt(now);
        membership.setUpdatedAt(now);
        membership.setCreatedBy(context.userId());
        membership.setUpdatedBy(context.userId());
        partyMembershipMapper.insert(membership);

        recordTransition(context, party.getId(), null, party.getLifecycleStatus(),
                active ? "auto_approved" : "creation_requested", null);
        return new CreatePartyResponse(
                party.getId(), party.getPid(), party.getLifecycleStatus(),
                membership.getId(), membership.getStatus());
    }

    @Override
    @Transactional
    public void approveParty(Long partyId) {
        Context context = requireActiveTenantMember();
        Party party = partyMapper.selectById(partyId);
        if (party == null || !context.tenantId().equals(party.getTenantId())
                || Boolean.TRUE.equals(party.getDeletedFlag())) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "Party not found");
        }
        if (!StatusConstants.PENDING.equals(party.getLifecycleStatus())) {
            throw new BusinessException(ResponseCode.BadParam, "Only a pending Party can be approved");
        }
        partyMapper.updateLifecycle(context.tenantId(), partyId, StatusConstants.ACTIVE, context.userId());
        partyMembershipMapper.activatePendingForParty(context.tenantId(), partyId, context.userId());
        recordTransition(context, partyId, StatusConstants.PENDING, StatusConstants.ACTIVE,
                "approved", null);
    }

    @Override
    @Transactional
    public ActorSwitchResponse switchActor(
            Long partyId,
            String currentToken,
            String ipAddress,
            String userAgent) {
        if (!systemModeService.isActorSwitchEnabled()) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "Actor switching is disabled");
        }
        Context context = requireActiveTenantMember();
        Long applicationId = jwtUtil.extractApplicationId(currentToken);
        Long loginChannelId = jwtUtil.extractLoginChannelId(currentToken);
        PartyActorOption actor = actorCandidateResolver.resolveActiveCandidate(
                context.tenantId(), context.tenantMemberId(), partyId,
                applicationId, loginChannelId);
        if (actor == null) {
            throw new BusinessException(ResponseCode.FORBIDDEN,
                    "No active Party membership for the selected Actor");
        }

        User user = userService.findByUserId(context.userId());
        if (user == null || Boolean.TRUE.equals(user.getDeletedFlag())) {
            throw new BusinessException(ResponseCode.Unauthorized, "User no longer exists");
        }

        long nextContextVersion = actorPreferenceMapper.advanceContextVersion(
                UlidGenerator.generate(),
                context.tenantId(),
                context.tenantMemberId(),
                partyId,
                jwtUtil.extractContextVersion(currentToken) + 1);
        int securityVersion = user.getSecurityVersion() == null ? 0 : user.getSecurityVersion();
        SessionTokenContext tokenContext = new SessionTokenContext(
                context.tenantId(),
                context.tenantMemberId(),
                applicationId,
                loginChannelId,
                ExecutionScope.PARTY,
                actor.getPartyId(),
                actor.getPartyMembershipId(),
                SessionStage.READY,
                nextContextVersion,
                securityVersion);
        String newToken = jwtUtil.generateTokenWithContext(toUserDetails(user), user.getPid(), tokenContext);

        // Persist the replacement before revoking the old token. A persistence failure leaves
        // the caller's existing session intact; a successful replacement invalidates it.
        sessionManagementService.createSession(user.getId(), newToken, ipAddress, userAgent);
        sessionManagementService.revokeSessionByToken(currentToken);
        return new ActorSwitchResponse(
                newToken,
                ExecutionScope.PARTY.getCode(),
                actor.getPartyId(),
                actor.getPartyMembershipId(),
                SessionStage.READY.getCode(),
                nextContextVersion);
    }

    private Context requireActiveTenantMember() {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        Long tenantMemberId = MetaContext.getCurrentMemberId();
        if (tenantId == null || userId == null || tenantMemberId == null) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "An active tenant membership is required");
        }
        TenantMember member = tenantMemberService.findByTenantIdAndUserId(tenantId, userId);
        if (member == null || !tenantMemberId.equals(member.getId())
                || !StatusConstants.ACTIVE.equalsIgnoreCase(member.getStatus())
                || Boolean.TRUE.equals(member.getDeletedFlag())) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "Tenant membership is not active");
        }
        return new Context(tenantId, tenantMemberId, userId);
    }

    private void recordTransition(
            Context context,
            Long partyId,
            String from,
            String to,
            String reasonCode,
            String reason) {
        PartyLifecycleTransition transition = new PartyLifecycleTransition();
        transition.setPid(UlidGenerator.generate());
        transition.setTenantId(context.tenantId());
        transition.setPartyId(partyId);
        transition.setFromStatus(from);
        transition.setToStatus(to);
        transition.setReasonCode(reasonCode);
        transition.setReason(reason);
        transition.setRequestedBy(context.userId());
        transition.setApprovedBy("approved".equals(reasonCode) ? context.userId() : null);
        transition.setOccurredAt(Instant.now());
        lifecycleTransitionMapper.insert(transition);
    }

    private CustomUserDetails toUserDetails(User user) {
        return new CustomUserDetails(
                user.getEmail(),
                user.getPassword() == null ? "" : user.getPassword(),
                user.getId(),
                user.getPid(),
                Collections.singletonList(new SimpleGrantedAuthority("role_user")),
                user.isAccountNonExpired(),
                user.isAccountNonLocked(),
                user.isCredentialsNonExpired(),
                user.isEnabled());
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private record Context(Long tenantId, Long tenantMemberId, Long userId) {}
}
