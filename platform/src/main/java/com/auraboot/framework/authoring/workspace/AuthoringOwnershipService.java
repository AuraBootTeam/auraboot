package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.authoring.workspace.AuthoringOwnershipRepository.CreateTenantOverride;
import com.auraboot.framework.authoring.workspace.AuthoringOwnershipRepository.TenantOverrideRow;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.entity.PageSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.Set;

import static org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY;

/** Resolves inherited resources into tenant-owned authoring lineage without mutating the source. */
@Service
public class AuthoringOwnershipService {

    private static final String TENANT = "TENANT";
    private static final Set<String> SHARED_SCOPES = Set.of("PLATFORM", "APPLICATION");

    private final AuthoringOwnershipRepository repository;

    public AuthoringOwnershipService(AuthoringOwnershipRepository repository) {
        this.repository = repository;
    }

    public OwnershipContext resolve(
            PageSchema page,
            long tenantId,
            long envId,
            long actorUserId,
            long baseVersion,
            String baseChecksum) {
        String sourceScope = sourceScope(page);
        if (TENANT.equals(sourceScope)) {
            return new OwnershipContext(
                    TENANT, TENANT, page.getPid(), null, "DESIGN_STUDIO", false);
        }
        TenantOverrideRow override = repository.findOrCreate(new CreateTenantOverride(
                UniqueIdGenerator.generate(), tenantId, envId, actorUserId,
                page.getPid(), sourceScope, baseVersion, baseChecksum));
        return new OwnershipContext(
                TENANT, sourceScope, page.getPid(), override.pid(), "TENANT_OVERRIDE",
                override.created());
    }

    public ObjectNode decorate(JsonNode snapshot, OwnershipContext ownership) {
        if (!(snapshot.deepCopy() instanceof ObjectNode decorated)) {
            throw new ResponseStatusException(
                    UNPROCESSABLE_ENTITY, "authoring.ownership.snapshot-object-required");
        }
        decorated.put("ownershipScope", ownership.ownershipScope());
        decorated.put("sourceOwnershipScope", ownership.sourceOwnershipScope());
        decorated.put("sourcePagePid", ownership.sourceResourcePid());
        if (ownership.overridePid() != null) {
            decorated.put("overridePid", ownership.overridePid());
        } else {
            decorated.remove("overridePid");
        }
        return decorated;
    }

    private String sourceScope(PageSchema page) {
        String declared = page.getOwnershipScope();
        if (declared == null || declared.isBlank()) {
            if (Boolean.TRUE.equals(page.getIsTemplate())) {
                return "PLATFORM";
            }
            return page.getPluginPid() == null ? TENANT : "APPLICATION";
        }
        String normalized = declared.trim().toUpperCase(Locale.ROOT);
        if (!TENANT.equals(normalized) && !SHARED_SCOPES.contains(normalized)) {
            throw new ResponseStatusException(
                    UNPROCESSABLE_ENTITY, "authoring.ownership.scope-unsupported");
        }
        return normalized;
    }

    public record OwnershipContext(
            String ownershipScope,
            String sourceOwnershipScope,
            String sourceResourcePid,
            String overridePid,
            String origin,
            boolean overrideCreated) {
    }
}
