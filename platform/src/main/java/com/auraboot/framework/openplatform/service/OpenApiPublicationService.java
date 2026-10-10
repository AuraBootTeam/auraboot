package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.StatusConstants;
import com.auraboot.framework.openplatform.mapper.ApplicationInstallationMapper;
import com.auraboot.framework.openplatform.mapper.OpenApiPublicationMapper;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** Tenant-owned draft editing and atomic immutable publication. */
@Service
@RequiredArgsConstructor
public class OpenApiPublicationService {
    private static final String WITHDRAWN = "withdrawn";
    private final OpenApiPublicationMapper mapper;
    private final ApplicationInstallationMapper installations;
    private final OpenApiPublicationValidator validator;
    private final UserPermissionService permissions;
    private final ObjectMapper json;

    public record PublicationView(String code, OpenApiResourceContract draft, long draftRevision,
                                  Integer activeVersion, String status) { }
    public record DraftRequest(long expectedRevision, OpenApiResourceContract definition) { }
    public record RevisionRequest(long expectedRevision) { }

    public List<PublicationView> list() {
        requireAdmin();
        return mapper.list(tenant()).stream().map(row -> new PublicationView(row.code(),
                decode(row.draft(), OpenApiResourceContract.class), row.draftRevision(), row.activeVersion(), row.status())).toList();
    }

    @Transactional
    public PublicationView saveDraft(String code, DraftRequest request) {
        requireAdmin();
        if (request.definition() == null || !code.equals(request.definition().code()) || request.expectedRevision() < 0) {
            throw new IllegalArgumentException("Publication code and expected draft revision required");
        }
        validator.validate(request.definition());
        String definition = encode(request.definition());
        int changed = request.expectedRevision() == 0
                ? mapper.create(tenant(), code, definition, actor())
                : mapper.updateDraft(tenant(), code, definition, actor(), request.expectedRevision());
        if (changed != 1) throw new PublicationConflictException("Publication draft revision changed");
        var row = mapper.lock(tenant(), code);
        mapper.audit(tenant(), code, null, "draft", actor(), row.draftRevision());
        return view(row);
    }

    @Transactional
    public PublicationView publish(String code, long expectedRevision) {
        requireAdmin();
        var row = requireLocked(code, expectedRevision);
        OpenApiResourceContract contract = decode(row.draft(), OpenApiResourceContract.class);
        validator.validate(contract);
        if (row.activeVersion() != null) {
            OpenApiResourceContract previous = decode(mapper.snapshot(tenant(), code, row.activeVersion()), OpenApiResourceContract.class);
            contract.requireCompatibleWith(previous);
            if (contract.schemaVersion() <= previous.schemaVersion()) {
                throw new PublicationConflictException("New snapshot requires a greater schema version");
            }
        }
        mapper.insertSnapshot(tenant(), code, contract.schemaVersion(), encode(contract), actor());
        mapper.activate(tenant(), code, contract.schemaVersion(), StatusConstants.PUBLISHED, actor());
        mapper.audit(tenant(), code, null, "publish", actor(), row.draftRevision());
        return view(mapper.lock(tenant(), code));
    }

    @Transactional
    public PublicationView withdraw(String code, long expectedRevision) {
        requireAdmin();
        var row = requireLocked(code, expectedRevision);
        mapper.activate(tenant(), code, row.activeVersion(), WITHDRAWN, actor());
        mapper.audit(tenant(), code, null, "withdraw", actor(), row.draftRevision());
        return view(mapper.lock(tenant(), code));
    }

    @Transactional
    public void grant(String installationPid, String code, OpenApiInstallationPolicy policy) {
        requireAdmin();
        // Serializes grant validation with publication changes.
        if (mapper.lock(tenant(), code) == null) throw new IllegalArgumentException("Publication not found");
        var contract = active(tenant(), code).orElseThrow(() -> new IllegalArgumentException("Published resource required"));
        policy.validate(contract);
        var installation = installations.findByTenantAndPid(tenant(), installationPid);
        if (installation == null || !StatusConstants.ACTIVE.equals(installation.getStatus())) throw new IllegalArgumentException("Active installation required");
        mapper.grant(tenant(), installation.getId(), code, encode(policy), actor());
        mapper.audit(tenant(), code, installation.getId(), "grant", actor(), contract.schemaVersion());
    }

    @Transactional
    public void revoke(String installationPid, String code) {
        requireAdmin();
        var installation = installations.findByTenantAndPid(tenant(), installationPid);
        if (installation == null) throw new IllegalArgumentException("Installation not found");
        mapper.revoke(tenant(), installation.getId(), code);
        mapper.audit(tenant(), code, installation.getId(), "revoke", actor(), 0);
    }

    public Optional<OpenApiResourceContract> active(Long tenantId, String code) {
        String definition = mapper.active(tenantId, code);
        return definition == null ? Optional.empty() : Optional.of(decode(definition, OpenApiResourceContract.class));
    }

    public OpenApiInstallationPolicy requirePolicy(Long tenantId, Long installationId, OpenApiResourceContract contract) {
        String value = mapper.policy(tenantId, installationId, contract.code());
        if (value == null) throw new AccessDeniedException("Resource installation grant required");
        var policy = decode(value, OpenApiInstallationPolicy.class);
        policy.validate(contract);
        return policy;
    }

    private OpenApiPublicationMapper.PublicationRow requireLocked(String code, long expectedRevision) {
        var row = mapper.lock(tenant(), code);
        if (row == null) throw new IllegalArgumentException("Publication not found");
        if (row.draftRevision() != expectedRevision) throw new PublicationConflictException("Publication draft revision changed");
        return row;
    }

    private PublicationView view(OpenApiPublicationMapper.PublicationRow row) {
        return new PublicationView(row.code(), decode(row.draft(), OpenApiResourceContract.class),
                row.draftRevision(), row.activeVersion(), row.status());
    }

    private void requireAdmin() {
        Long userId = MetaContext.getCurrentUserId();
        if (userId == null || !permissions.hasPermission(userId, MetaPermission.SYS_CONNECTOR_MANAGE)) {
            throw new AccessDeniedException("Open Platform administrator permission required");
        }
        actor();
    }

    private Long tenant() {
        Long id = MetaContext.getCurrentTenantId();
        if (id == null) throw new AccessDeniedException("Tenant context required");
        return id;
    }

    private String actor() {
        String pid = MetaContext.getCurrentUserPid();
        if (MetaContext.getCurrentUserId() == null || pid == null) throw new AccessDeniedException("Human publication administrator required");
        return pid;
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        // CATCH: explicit JSON boundary conversion preserves the original serialization failure.
        catch (JsonProcessingException exception) { throw new IllegalArgumentException("Publication cannot be serialized", exception); }
    }

    private <T> T decode(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        // CATCH: a corrupt stored contract must fail closed, with its original cause.
        catch (JsonProcessingException exception) { throw new IllegalStateException("Stored publication contract is invalid", exception); }
    }

    public static class PublicationConflictException extends org.springframework.web.server.ResponseStatusException {
        public PublicationConflictException(String message) { super(org.springframework.http.HttpStatus.CONFLICT, message); }
    }
}
