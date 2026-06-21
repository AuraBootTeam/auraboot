package com.auraboot.framework.behavior.sitekey;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.DataAccessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Command handler for the site-key registry.
 *
 * <p>Owns persistence for two commands ({@link #requiresDslPersistence} returns
 * {@code false}, so the generic field-map insert is skipped and this handler is
 * the sole writer — same pattern as the shipped Agent approval handler):
 * <ul>
 *   <li>{@code behavior_site_key:create} — the user submits only {@code name}; the
 *       server generates the public {@code abk_} key (globally unique) and inserts
 *       the row with {@code status=active}. The key is never user-supplied.</li>
 *   <li>{@code behavior_site_key:disable} — flips {@code status} to {@code disabled}
 *       for the target record and evicts the resolver cache so it stops resolving
 *       to its tenant.</li>
 * </ul>
 *
 * <p>No self-heal / fallback: missing inputs or records raise a {@link BusinessException}.
 */
@Slf4j
@Component
public class SiteKeyCommandHandler implements CommandHandlerExtension {

    static final String MODEL = "behavior_site_key";
    static final String CREATE = "behavior_site_key:create";
    static final String DISABLE = "behavior_site_key:disable";

    /** Bounded retries to dodge the astronomically unlikely key collision. */
    private static final int MAX_KEY_RETRIES = 5;

    private final SiteKeyRegistry registry;

    public SiteKeyCommandHandler(SiteKeyRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String getCommandType() {
        return CREATE;
    }

    @Override
    public boolean supports(String commandType) {
        return CREATE.equals(commandType) || DISABLE.equals(commandType);
    }

    @Override
    public Set<String> getSupportedCommandTypes() {
        return Set.of(CREATE, DISABLE);
    }

    /**
     * This handler owns its persistence for both commands, so the generic DSL
     * field-map / state-transition persistence must not run first. Mirrors
     * {@code AgentApprovalCommandHandler}.
     */
    @Override
    public boolean requiresDslPersistence(String commandType,
                                          Map<String, Object> execConfig,
                                          CommandExecuteRequest request) {
        return false;
    }

    @Override
    public Object execute(CommandContext context) {
        String commandType = context.commandType();
        if (CREATE.equals(commandType)) {
            return create(context);
        }
        if (DISABLE.equals(commandType)) {
            return disable(context);
        }
        throw new BusinessException(ResponseCode.BadParam, "Unsupported site-key command: " + commandType);
    }

    private Map<String, Object> create(CommandContext context) {
        DataAccessor dataAccessor = requireDataAccessor(context);
        Map<String, Object> payload = context.payload() != null ? context.payload() : Map.of();

        Object nameValue = payload.get("name");
        if (!(nameValue instanceof String name) || name.isBlank()) {
            throw new BusinessException(ResponseCode.BadParam, "Site key name is required");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name.trim());
        data.put("site_key", generateUniqueKey());
        data.put("status", "active");
        // origin_allowlist is server-managed in SP2; SP1 only persists what the form sends.
        Object originAllowlist = payload.get("origin_allowlist");
        if (originAllowlist != null) {
            data.put("origin_allowlist", originAllowlist);
        }

        Map<String, Object> created = dataAccessor.create(MODEL, data);
        log.info("Created site key for tenant={} name='{}'", context.tenantId(), name.trim());
        return created;
    }

    private Map<String, Object> disable(CommandContext context) {
        DataAccessor dataAccessor = requireDataAccessor(context);
        String recordId = context.recordId();
        if (recordId == null || recordId.isBlank()) {
            throw new BusinessException(ResponseCode.BadParam, "Site key record id is required to disable");
        }

        Map<String, Object> existing = dataAccessor.getById(MODEL, recordId);
        if (existing == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "Site key not found: " + recordId);
        }

        Map<String, Object> updated = dataAccessor.update(MODEL, recordId, Map.of("status", "disabled"));

        Object siteKey = existing.get("site_key");
        if (siteKey instanceof String key) {
            registry.evict(key);
        }
        log.info("Disabled site key tenant={} recordId={}", context.tenantId(), recordId);
        return updated;
    }

    private String generateUniqueKey() {
        for (int attempt = 0; attempt < MAX_KEY_RETRIES; attempt++) {
            String candidate = SiteKeyGenerator.generate();
            if (!registry.existsAnyTenant(candidate)) {
                return candidate;
            }
            log.warn("Site key collision on attempt {} — regenerating", attempt + 1);
        }
        throw new BusinessException(ResponseCode.SystemError,
                "Failed to generate a unique site key after " + MAX_KEY_RETRIES + " attempts");
    }

    private DataAccessor requireDataAccessor(CommandContext context) {
        DataAccessor dataAccessor = context.dataAccessor();
        if (dataAccessor == null) {
            throw new BusinessException(ResponseCode.SystemError, "DataAccessor unavailable for site-key command");
        }
        return dataAccessor;
    }
}
