package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.application.security.ExternalMachineAuthenticator.MachinePrincipal;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.openplatform.mapper.*;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Reuses immutable publication revisions, installation grants and audit storage under action: keys. */
@Service
@RequiredArgsConstructor
public class OpenApiActionPublicationService {
    private final OpenApiPublicationMapper mapper;
    private final ApplicationInstallationMapper installations;
    private final OpenApiActionValidator validator;
    private final UserPermissionService permissions;
    private final ObjectMapper json;
    public record DraftRequest(long expectedRevision, OpenApiActionContract definition) { }
    public record View(String code, OpenApiActionContract draft, long draftRevision, Integer activeVersion, String status) { }
    public record GrantView(String code, String status, OpenApiActionContract contract, OpenApiActionPolicy policy) { }
    public record Authority(OpenApiActionContract contract, OpenApiActionPolicy policy, Long installationId) { }

    public List<View> list() { requireAdmin(); return mapper.listActions(tenant()).stream().map(this::view).toList(); }
    public List<OpenApiActionValidator.SourceView> sources(String resourceCode) { requireAdmin(); return validator.sources(tenant(), resourceCode); }
    @Transactional public View saveDraft(String code, DraftRequest request) {
        requireAdmin();
        if (request.definition() == null || !code.equals(request.definition().code()) || request.expectedRevision() < 0)
            throw new IllegalArgumentException("Business API code and draft revision required");
        validator.validate(tenant(), request.definition());
        int changed = request.expectedRevision() == 0 ? mapper.create(tenant(), key(code), encode(request.definition()), actor())
                : mapper.updateDraft(tenant(), key(code), encode(request.definition()), actor(), request.expectedRevision());
        if (changed != 1) throw conflict();
        var row = mapper.lock(tenant(), key(code)); mapper.audit(tenant(), key(code), null, "draft", actor(), row.draftRevision()); return view(row);
    }
    @Transactional public View publish(String code, long revision) {
        requireAdmin(); var row = lock(code, revision); var contract = decode(row.draft(), OpenApiActionContract.class);
        validator.validate(tenant(), contract);
        if (row.activeVersion() != null) {
            contract.requireCompatibleWith(decode(mapper.snapshot(tenant(), key(code), row.activeVersion()), OpenApiActionContract.class));
            if (contract.schemaVersion() <= row.activeVersion()) throw conflict();
        }
        mapper.insertSnapshot(tenant(), key(code), contract.schemaVersion(), encode(contract), actor());
        mapper.activate(tenant(), key(code), contract.schemaVersion(), "published", actor());
        mapper.audit(tenant(), key(code), null, "publish", actor(), revision); return view(mapper.lock(tenant(), key(code)));
    }
    @Transactional public View withdraw(String code, long revision) {
        requireAdmin(); var row = lock(code, revision); mapper.activate(tenant(), key(code), row.activeVersion(), "withdrawn", actor());
        mapper.audit(tenant(), key(code), null, "withdraw", actor(), revision); return view(mapper.lock(tenant(), key(code)));
    }
    public List<GrantView> installationGrants(String installationPid) {
        requireAdmin(); var installation = installation(installationPid);
        return mapper.listActions(tenant()).stream().filter(row -> row.activeVersion() != null).map(row -> {
            var contract = decode(mapper.snapshot(tenant(), row.code(), row.activeVersion()), OpenApiActionContract.class);
            String policy = mapper.storedPolicy(tenant(), installation.getId(), row.code());
            return new GrantView(contract.code(), row.status(), contract, policy == null ? null : decode(policy, OpenApiActionPolicy.class));
        }).toList();
    }
    @Transactional public void grant(String installationPid, String code, OpenApiActionPolicy policy) {
        requireAdmin();
        if (mapper.lock(tenant(), key(code)) == null) throw new IllegalArgumentException("Business API publication not found");
        var contract = active(tenant(), code).orElseThrow(() -> new IllegalArgumentException("Published business API required"));
        policy.validate(contract); var installation = installations.findActiveByTenantAndPid(tenant(), installationPid);
        if (installation == null) throw new IllegalArgumentException("Active application installation required");
        mapper.grant(tenant(), installation.getId(), key(code), encode(policy), actor());
        mapper.audit(tenant(), key(code), installation.getId(), "grant", actor(), contract.schemaVersion());
    }
    @Transactional public void revoke(String installationPid, String code) {
        requireAdmin(); var installation = installation(installationPid); mapper.revoke(tenant(), installation.getId(), key(code));
        mapper.audit(tenant(), key(code), installation.getId(), "revoke", actor(), 0);
    }
    public OpenApiActionContract publishedForAdministration(String code) {
        requireAdmin(); return active(tenant(), code).orElseThrow(() -> new IllegalArgumentException("Published business API required"));
    }
    public Optional<OpenApiActionContract> active(Long tenant, String code) {
        String value = mapper.active(tenant, key(code)); return value == null ? Optional.empty() : Optional.of(decode(value, OpenApiActionContract.class));
    }
    public List<OpenApiActionContract> published(Long tenant) {
        return mapper.activeActionDefinitions(tenant).stream().map(value -> decode(value, OpenApiActionContract.class)).toList();
    }
    public Optional<Authority> authority(MachinePrincipal principal, String code) {
        var row = mapper.authority(principal.tenantId(), principal.installationPid(), principal.tokenPid(), key(code), "execute");
        return row == null ? Optional.empty() : Optional.of(new Authority(decode(row.definition(), OpenApiActionContract.class),
                decode(row.policy(), OpenApiActionPolicy.class), row.installationId()));
    }
    private com.auraboot.framework.openplatform.entity.ApplicationInstallation installation(String pid) {
        var installation = installations.findByTenantAndPid(tenant(), pid);
        if (installation == null) throw new IllegalArgumentException("Installation not found"); return installation;
    }
    private OpenApiPublicationMapper.PublicationRow lock(String code, long revision) {
        var row = mapper.lock(tenant(), key(code));
        if (row == null) throw new IllegalArgumentException("Business API publication not found");
        if (row.draftRevision() != revision) throw conflict(); return row;
    }
    private View view(OpenApiPublicationMapper.PublicationRow row) {
        var contract = decode(row.draft(), OpenApiActionContract.class); return new View(contract.code(), contract, row.draftRevision(), row.activeVersion(), row.status());
    }
    private String key(String code) {
        if (code == null || !code.matches("[a-z][a-z0-9.-]{1,92}")) throw new IllegalArgumentException("Invalid business API code"); return "action:" + code;
    }
    private void requireAdmin() {
        Long user = MetaContext.getCurrentUserId();
        if (user == null || !permissions.hasPermission(user, MetaPermission.SYS_CONNECTOR_MANAGE)) throw new AccessDeniedException("Open Platform administrator permission required"); actor();
    }
    private Long tenant() {
        Long id = MetaContext.getCurrentTenantId(); if (id == null) throw new AccessDeniedException("Tenant context required"); return id;
    }
    private String actor() {
        String pid = MetaContext.getCurrentUserPid(); if (MetaContext.getCurrentUserId() == null || pid == null) throw new AccessDeniedException("Human publication administrator required"); return pid;
    }
    private OpenApiPublicationService.PublicationConflictException conflict() { return new OpenApiPublicationService.PublicationConflictException("Business API draft or schema revision changed"); }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); } catch (JsonProcessingException failure) { throw new IllegalArgumentException("Business API cannot be encoded", failure); }
    }
    private <T> T decode(String value, Class<T> type) {
        try { return json.readValue(value, type); } catch (JsonProcessingException failure) { throw new IllegalStateException("Stored business API is invalid", failure); }
    }
}
