package com.auraboot.framework.openplatform.service;

import com.auraboot.framework.application.security.ExternalMachineAuthenticator.MachinePrincipal;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.meta.service.NamedQueryService;
import com.auraboot.framework.openplatform.mapper.*;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Reuses the resource publication snapshot/grant/audit store with a disjoint query key namespace. */
@Service
@RequiredArgsConstructor
public class OpenApiQueryPublicationService {
    private final OpenApiPublicationMapper mapper;
    private final ApplicationInstallationMapper installations;
    private final UserPermissionService permissions;
    private final OpenApiQueryValidator validator;
    private final NamedQueryService queries;
    private final ObjectMapper json;
    public record DraftRequest(long expectedRevision, OpenApiQueryContract definition) { }
    public record View(String code, OpenApiQueryContract draft, long draftRevision, Integer activeVersion, String status) { }
    public record Authority(OpenApiQueryContract contract, OpenApiQueryPolicy policy, Long installationId) { }

    public List<View> list() { requireAdmin(); return mapper.listQueries(tenant()).stream().map(this::view).toList(); }
    public NamedQueryService.ExecutionDefinition source(String code) { requireAdmin(); return queries.describeExecution(code); }
    @Transactional public View saveDraft(String code, DraftRequest request) {
        requireAdmin();
        if (request.definition() == null || !code.equals(request.definition().code()) || request.expectedRevision() < 0)
            throw new IllegalArgumentException("Query code and draft revision required");
        validator.validate(tenant(), request.definition());
        String key = request.definition().storageCode();
        int changed = request.expectedRevision() == 0 ? mapper.create(tenant(), key, encode(request.definition()), actor())
                : mapper.updateDraft(tenant(), key, encode(request.definition()), actor(), request.expectedRevision());
        if (changed != 1) throw conflict();
        var row = mapper.lock(tenant(), key); mapper.audit(tenant(), key, null, "draft", actor(), row.draftRevision());
        return view(row);
    }
    @Transactional public View publish(String code, long revision) {
        requireAdmin(); var row = lock(code, revision); var contract = decode(row.draft(), OpenApiQueryContract.class);
        validator.validate(tenant(), contract);
        if (row.activeVersion() != null) {
            contract.requireCompatibleWith(decode(mapper.snapshot(tenant(), key(code), row.activeVersion()), OpenApiQueryContract.class));
            if (contract.schemaVersion() <= row.activeVersion()) throw conflict();
        }
        mapper.insertSnapshot(tenant(), key(code), contract.schemaVersion(), encode(contract), actor());
        mapper.activate(tenant(), key(code), contract.schemaVersion(), "published", actor());
        mapper.audit(tenant(), key(code), null, "publish", actor(), revision); return view(mapper.lock(tenant(), key(code)));
    }
    @Transactional public View withdraw(String code, long revision) {
        requireAdmin();var row=lock(code,revision);mapper.activate(tenant(),key(code),row.activeVersion(),"withdrawn",actor());
        mapper.audit(tenant(),key(code),null,"withdraw",actor(),revision);return view(mapper.lock(tenant(),key(code)));
    }
    @Transactional public void grant(String installationPid, String code, OpenApiQueryPolicy policy) {
        requireAdmin();
        if (mapper.lock(tenant(), key(code)) == null) throw new IllegalArgumentException("Query publication not found");
        var contract = active(tenant(), code).orElseThrow(() -> new IllegalArgumentException("Published query required"));policy.validate(contract);
        var installation = installations.findByTenantAndPid(tenant(), installationPid);
        if (installation == null || !"active".equals(installation.getStatus())) throw new IllegalArgumentException("Active installation required");
        mapper.grant(tenant(), installation.getId(), key(code), encode(policy), actor());
        mapper.audit(tenant(), key(code), installation.getId(), "grant", actor(), contract.schemaVersion());
    }
    @Transactional public void revoke(String installationPid, String code) {
        requireAdmin(); var installation=installations.findByTenantAndPid(tenant(),installationPid);
        if(installation==null)throw new IllegalArgumentException("Installation not found");mapper.revoke(tenant(),installation.getId(),key(code));
        mapper.audit(tenant(),key(code),installation.getId(),"revoke",actor(),0);
    }
    public Optional<OpenApiQueryContract> active(Long tenant, String code) {
        String value=mapper.active(tenant,key(code));return value==null?Optional.empty():Optional.of(decode(value,OpenApiQueryContract.class));
    }
    public List<OpenApiQueryContract> published(Long tenant) {
        return mapper.activeQueryDefinitions(tenant).stream().map(value->decode(value,OpenApiQueryContract.class)).toList();
    }
    public Optional<Authority> authority(MachinePrincipal principal,String code) {
        var row=mapper.authority(principal.tenantId(),principal.installationPid(),principal.tokenPid(),key(code),"execute");
        return row==null?Optional.empty():Optional.of(new Authority(decode(row.definition(),OpenApiQueryContract.class),
                decode(row.policy(),OpenApiQueryPolicy.class),row.installationId()));
    }
    private OpenApiPublicationMapper.PublicationRow lock(String code,long revision) {
        var row=mapper.lock(tenant(),key(code));if(row==null)throw new IllegalArgumentException("Query publication not found");
        if(row.draftRevision()!=revision)throw conflict();return row;
    }
    private View view(OpenApiPublicationMapper.PublicationRow row) {
        var contract=decode(row.draft(),OpenApiQueryContract.class);return new View(contract.code(),contract,row.draftRevision(),row.activeVersion(),row.status());
    }
    private String key(String code) {
        if(code==null||!code.matches("[a-z][a-z0-9.-]{1,93}"))throw new IllegalArgumentException("Invalid public query code");return "query:"+code;
    }
    private void requireAdmin() {
        Long user=MetaContext.getCurrentUserId();if(user==null||!permissions.hasPermission(user, MetaPermission.SYS_CONNECTOR_MANAGE))
            throw new AccessDeniedException("Open Platform administrator permission required");actor();
    }
    private Long tenant() { Long id=MetaContext.getCurrentTenantId();if(id==null)throw new AccessDeniedException("Tenant context required");return id; }
    private String actor() {String pid=MetaContext.getCurrentUserPid();if(MetaContext.getCurrentUserId()==null||pid==null)
        throw new AccessDeniedException("Human publication administrator required");return pid;}
    private OpenApiPublicationService.PublicationConflictException conflict() {return new OpenApiPublicationService.PublicationConflictException("Query publication draft or schema revision changed");}
    private String encode(Object value) {
        try{return json.writeValueAsString(value);}catch(JsonProcessingException failure){throw new IllegalArgumentException("Query publication cannot be serialized",failure);}
    }
    private <T>T decode(String value,Class<T> type) {
        try{return json.readValue(value,type);}catch(JsonProcessingException failure){throw new IllegalStateException("Stored query publication is invalid",failure);}
    }
}
