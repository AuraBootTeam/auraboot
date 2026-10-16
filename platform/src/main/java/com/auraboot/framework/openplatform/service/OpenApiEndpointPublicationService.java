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

/** Reuses immutable publication revisions, installation grants and audit storage under endpoint: keys. */
@Service
@RequiredArgsConstructor
public class OpenApiEndpointPublicationService {
    private final OpenApiPublicationMapper mapper;
    private final ApplicationInstallationMapper installations;
    private final OpenApiEndpointValidator validator;
    private final UserPermissionService permissions;
    private final ObjectMapper json;
    public record DraftRequest(long expectedRevision, OpenApiEndpointContract definition) { }
    public record View(String code, OpenApiEndpointContract draft, long draftRevision, Integer activeVersion, String status) { }
    public record GrantView(String code, String status, OpenApiEndpointContract contract, OpenApiEndpointPolicy policy) { }
    public record Authority(OpenApiEndpointContract contract, OpenApiEndpointPolicy policy, Long installationId) { }

    public List<View> list() { requireAdmin(); return mapper.listEndpoints(tenant()).stream().map(this::view).toList(); }
    private final OpenApiEndpointRegistry registry;
    public List<OpenApiEndpointRegistry.Candidate> candidates(String search) { requireAdmin(); return registry.candidates(search); }
    public OpenApiEndpointValidator.Description source(String candidateCode, String bindingCode) {
        requireAdmin(); return validator.describe(tenant(), candidateCode, bindingCode);
    }
    public Map<String, Object> document(String code) { return validator.document(tenant(), publishedForAdministration(code)); }
    public String types(String code) { return validator.types(tenant(), publishedForAdministration(code)); }
    @Transactional public View saveDraft(String code, DraftRequest request) {
        requireAdmin();
        if (request.definition() == null || !code.equals(request.definition().code()) || request.expectedRevision() < 0)
            throw new IllegalArgumentException("Endpoint code and draft revision required");
        validator.validate(tenant(), request.definition());
        int changed = request.expectedRevision() == 0 ? mapper.create(tenant(), key(code), encode(request.definition()), actor())
                : mapper.updateDraft(tenant(), key(code), encode(request.definition()), actor(), request.expectedRevision());
        if (changed != 1) throw conflict();
        var row = mapper.lock(tenant(), key(code)); mapper.audit(tenant(), key(code), null, "draft", actor(), row.draftRevision()); return view(row);
    }
    @Transactional public View publish(String code, long revision) {
        requireAdmin(); var row = lock(code, revision); var contract = decode(row.draft(), OpenApiEndpointContract.class);
        validator.validate(tenant(), contract);
        if (row.activeVersion() != null) {
            contract.requireCompatibleWith(decode(mapper.snapshot(tenant(), key(code), row.activeVersion()), OpenApiEndpointContract.class));
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
        return mapper.listEndpoints(tenant()).stream().filter(row -> row.activeVersion() != null).map(row -> {
            var contract = decode(mapper.snapshot(tenant(), row.code(), row.activeVersion()), OpenApiEndpointContract.class);
            String policy = mapper.storedPolicy(tenant(), installation.getId(), row.code());
            return new GrantView(contract.code(), row.status(), contract, policy == null ? null : decode(policy, OpenApiEndpointPolicy.class));
        }).toList();
    }
    @Transactional public void grant(String installationPid, String code, OpenApiEndpointPolicy policy) {
        requireAdmin();
        if (mapper.lock(tenant(), key(code)) == null) throw new IllegalArgumentException("Endpoint publication not found");
        var contract = active(tenant(), code).orElseThrow(() -> new IllegalArgumentException("Published endpoint required"));
        if (policy == null) throw new IllegalArgumentException("Endpoint policy required");
        var installation = installations.findActiveByTenantAndPid(tenant(), installationPid);
        if (installation == null) throw new IllegalArgumentException("Active application installation required");
        mapper.grant(tenant(), installation.getId(), key(code), encode(policy), actor());
        mapper.audit(tenant(), key(code), installation.getId(), "grant", actor(), contract.schemaVersion());
    }
    @Transactional public void revoke(String installationPid, String code) {
        requireAdmin(); var installation = installation(installationPid); mapper.revoke(tenant(), installation.getId(), key(code));
        mapper.audit(tenant(), key(code), installation.getId(), "revoke", actor(), 0);
    }
    public OpenApiEndpointContract publishedForAdministration(String code) {
        requireAdmin(); return active(tenant(), code).orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "open_api_endpoint_not_published"));
    }
    public Optional<OpenApiEndpointContract> active(Long tenant, String code) {
        String value = mapper.active(tenant, key(code)); return value == null ? Optional.empty() : Optional.of(decode(value, OpenApiEndpointContract.class));
    }
    public List<OpenApiEndpointContract> published(Long tenant) {
        return mapper.activeEndpointDefinitions(tenant).stream().map(value -> decode(value, OpenApiEndpointContract.class)).toList();
    }
    public Optional<Authority> authority(MachinePrincipal principal, String code) {
        var row = mapper.authority(principal.tenantId(), principal.installationPid(), principal.tokenPid(), key(code), "invoke");
        return row == null ? Optional.empty() : Optional.of(new Authority(decode(row.definition(), OpenApiEndpointContract.class),
                decode(row.policy(), OpenApiEndpointPolicy.class), row.installationId()));
    }
    private com.auraboot.framework.openplatform.entity.ApplicationInstallation installation(String pid) {
        var installation = installations.findByTenantAndPid(tenant(), pid);
        if (installation == null) throw new IllegalArgumentException("Installation not found"); return installation;
    }
    private OpenApiPublicationMapper.PublicationRow lock(String code, long revision) {
        var row = mapper.lock(tenant(), key(code));
        if (row == null) throw new IllegalArgumentException("Endpoint publication not found");
        if (row.draftRevision() != revision) throw conflict(); return row;
    }
    private View view(OpenApiPublicationMapper.PublicationRow row) {
        var contract = decode(row.draft(), OpenApiEndpointContract.class); return new View(contract.code(), contract, row.draftRevision(), row.activeVersion(), row.status());
    }
    private String key(String code) {
        if (code == null || !code.matches("[a-z][a-z0-9.-]{1,90}")) throw new IllegalArgumentException("Invalid endpoint code"); return "endpoint:" + code;
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
    private OpenApiPublicationService.PublicationConflictException conflict() { return new OpenApiPublicationService.PublicationConflictException("Endpoint draft or schema revision changed"); }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); } catch (JsonProcessingException failure) { throw new IllegalArgumentException("Endpoint cannot be encoded", failure); }
    }
    private <T> T decode(String value, Class<T> type) {
        try { return json.readValue(value, type); } catch (JsonProcessingException failure) { throw new IllegalStateException("Stored endpoint is invalid", failure); }
    }
}
