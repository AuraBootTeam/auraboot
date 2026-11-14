package com.auraboot.framework.meta.synthesized;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.StatusConstants;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.mapper.CommandDefinitionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Lazily seeds the {@code dynamic.*} synthesized command definitions for the
 * current tenant when {@code aura.command.synthesized-dynamic-writes=true}.
 * Command definitions are tenant-scoped (TenantLine interceptor), so seeding
 * happens per tenant on first use rather than at startup; insertIdempotent
 * keeps concurrent first-writes safe.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicWriteCommandSeedService {

    private final CommandDefinitionMapper commandDefinitionMapper;

    /**
     * Ensures the definition exists for the current tenant and returns its
     * code. Safe to call on every synthesized write.
     */
    public String ensureSeeded(String code) {
        if (commandDefinitionMapper.findCurrentByCode(code) != null) {
            return code;
        }
        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null) {
            throw new IllegalStateException("Synthesized dynamic writes require an initialized tenant context");
        }
        CommandDefinition def = new CommandDefinition();
        def.setPid(UniqueIdGenerator.generate());
        def.setTenantId(tenantId);
        def.setCode(code);
        def.setDisplayName("Synthesized dynamic " + code.substring("dynamic.".length()).replace('_', ' '));
        def.setDescription("Platform-seeded synthesized command routing the dynamic CRUD surface through the pipeline (aura.command.synthesized-dynamic-writes)");
        def.setInputSchema("{}");
        def.setTargetModels("[]");
        // executionConfig drives the pipeline: handler + handler-owned invocation authorization
        def.setExecutionConfig("{\"handler\":\"" + DynamicWriteCommandHandler.HANDLER_CODE
                + "\",\"handlerParams\":{\"invocationAuthorization\":\"handler\"}}");
        def.setCmdRiskLevel("L2");
        def.setVersion(1);
        def.setIsCurrent(true);
        def.setRowVersion(1);
        def.setStatus(StatusConstants.PUBLISHED);
        def.setDeletedFlag(false);
        def.setCreatedAt(Instant.now());
        def.setUpdatedAt(Instant.now());
        commandDefinitionMapper.insertIdempotent(def);
        log.info("Seeded synthesized dynamic write command {} for tenant {}", code, tenantId);
        return code;
    }
}
