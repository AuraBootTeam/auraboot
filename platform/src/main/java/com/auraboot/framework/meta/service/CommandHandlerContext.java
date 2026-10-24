package com.auraboot.framework.meta.service;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * Context passed to CommandHandler during HANDLER phase execution.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Data
@Builder
public class CommandHandlerContext {

    private String commandCode;
    private String modelCode;
    private Map<String, Object> payload;
    private String operationType;
    /** Original client intent identity; never synthesized from mutable business fields. */
    private String clientRequestId;
    private Integer expectedVersion;
    private String targetRecordId;
    private Long tenantId;
    private Long userId;
    private Map<String, Object> fieldMapResults;
    private String ruleConfig;

    /**
     * When true, the enclosing CommandPipeline transaction will roll back
     * (set via {@code TransactionAspectSupport.setRollbackOnly()} in
     * {@code CommandExecutorImpl}). Handlers MUST early-return or switch to
     * a side-effect-free branch when {@code dryRun=true} — the rollback
     * only covers DB writes inside the same transaction. External effects
     * (HTTP, email, MQ, file system, caches) escape the rollback envelope.
     */
    private boolean dryRun;
}
