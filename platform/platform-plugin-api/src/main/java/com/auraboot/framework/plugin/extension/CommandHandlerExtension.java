package com.auraboot.framework.plugin.extension;

import org.pf4j.ExtensionPoint;

import java.util.Map;
import java.util.Set;

/**
 * Extension point for custom command handlers.
 * Plugins can implement this interface to provide custom command processing logic.
 *
 * Example usage:
 * <pre>
 * {@code
 * @Extension
 * public class MyCommandHandler implements CommandHandlerExtension {
 *     @Override
 *     public String getCommandType() {
 *         return "my-plugin:custom-action";
 *     }
 *
 *     @Override
 *     public Object execute(CommandContext context) {
 *         // Custom command logic
 *         return result;
 *     }
 * }
 * }
 * </pre>
 */
public interface CommandHandlerExtension extends ExtensionPoint {

    /**
     * Get the command type this handler processes.
     * Format: "namespace:command-name" (e.g., "billing:generate-invoice")
     *
     * @return command type identifier
     */
    String getCommandType();

    /**
     * Enumerate all command types this handler can process.
     *
     * <p>Runtime dispatch still calls {@link #supports(String)}, but import-time
     * validation needs a finite list of handler codes to register. Handlers that
     * override {@code supports(...)} for aliases must override this method with
     * the same set of supported codes.
     *
     * @return supported command type identifiers
     */
    default Set<String> getSupportedCommandTypes() {
        String commandType = getCommandType();
        return commandType == null ? Set.of() : Set.of(commandType);
    }

    /**
     * Execute the command.
     *
     * @param context command execution context
     * @return command result (can be null)
     * @throws Exception if execution fails
     */
    Object execute(CommandContext context) throws Exception;

    /**
     * Check if this handler supports the given command type.
     *
     * @param commandType the command type to check
     * @return true if this handler can process the command
     */
    default boolean supports(String commandType) {
        return getCommandType().equals(commandType);
    }

    /**
     * Get the priority of this handler.
     * Higher priority handlers are executed first.
     * Default is 0.
     *
     * @return handler priority
     */
    default int getPriority() {
        return 0;
    }

    /**
     * Declare whether this handler is safe to execute under
     * {@code CommandExecuteRequest.dryRun=true}.
     *
     * <p>The CommandPipeline transaction rollback only undoes writes made
     * through the pooled JDBC {@code DataSource}. External side effects —
     * outbound HTTP, email, MQ publishes, object-storage uploads, Redis,
     * external DBs, file writes — escape the rollback envelope and will
     * fire for real even when dry-run is requested.
     *
     * <p>Return {@code true} only when the handler either has no such
     * external side effects, or inspects {@link CommandContext#dryRun()}
     * internally and short-circuits every external call.
     *
     * <p>When this method returns {@code false} (default), HandlerPhase
     * skips the handler entirely under dry-run and logs the skip at INFO.
     *
     * @return true if the handler honours dry-run semantics
     * @since PR-56
     */
    default boolean supportsDryRun() {
        return false;
    }

    /** Well-known key for DataAccessor in the settings map. */
    String DATA_ACCESSOR_KEY = "__dataAccessor";

    /** Well-known key for BiTemporalAccessor in the settings map. */
    String BI_TEMPORAL_ACCESSOR_KEY = "__biTemporalAccessor";

    /** Well-known key for AiProviderAccessor in the settings map. */
    String AI_PROVIDER_ACCESSOR_KEY = AiProviderAccessor.SETTINGS_KEY;

    /**
     * Command execution context.
     */
    record CommandContext(
            Long tenantId,
            String pluginId,
            String namespace,
            String commandType,
            String modelCode,
            String recordId,
            Map<String, Object> payload,
            Map<String, Object> settings,
            boolean dryRun
    ) {
        /**
         * Get the DataAccessor from the settings map.
         * Returns null if not available.
         */
        public DataAccessor dataAccessor() {
            Object da = settings != null ? settings.get(DATA_ACCESSOR_KEY) : null;
            return da instanceof DataAccessor ? (DataAccessor) da : null;
        }

        /**
         * Get the BiTemporalAccessor from the settings map.
         * Returns null if BiTemporalService is not available (optional module).
         */
        public BiTemporalAccessor biTemporalAccessor() {
            Object bta = settings != null ? settings.get(BI_TEMPORAL_ACCESSOR_KEY) : null;
            return bta instanceof BiTemporalAccessor ? (BiTemporalAccessor) bta : null;
        }

        /**
         * Get the platform LLM provider accessor from the settings map.
         * Returns null if the host application has no provider bridge.
         */
        public AiProviderAccessor aiProviderAccessor() {
            Object accessor = settings != null ? settings.get(AI_PROVIDER_ACCESSOR_KEY) : null;
            return accessor instanceof AiProviderAccessor ? (AiProviderAccessor) accessor : null;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private Long tenantId;
            private String pluginId;
            private String namespace;
            private String commandType;
            private String modelCode;
            private String recordId;
            private Map<String, Object> payload;
            private Map<String, Object> settings;
            private boolean dryRun;

            public Builder tenantId(Long tenantId) {
                this.tenantId = tenantId;
                return this;
            }

            public Builder pluginId(String pluginId) {
                this.pluginId = pluginId;
                return this;
            }

            public Builder namespace(String namespace) {
                this.namespace = namespace;
                return this;
            }

            public Builder commandType(String commandType) {
                this.commandType = commandType;
                return this;
            }

            public Builder modelCode(String modelCode) {
                this.modelCode = modelCode;
                return this;
            }

            public Builder recordId(String recordId) {
                this.recordId = recordId;
                return this;
            }

            public Builder payload(Map<String, Object> payload) {
                this.payload = payload;
                return this;
            }

            public Builder settings(Map<String, Object> settings) {
                this.settings = settings;
                return this;
            }

            /**
             * When true, the enclosing CommandPipeline transaction will roll
             * back DB writes. Plugin handlers MUST early-return or switch to
             * a side-effect-free branch — external effects (HTTP / email /
             * MQ / file / cache) escape the rollback boundary.
             */
            public Builder dryRun(boolean dryRun) {
                this.dryRun = dryRun;
                return this;
            }

            public CommandContext build() {
                return new CommandContext(tenantId, pluginId, namespace, commandType, modelCode, recordId, payload, settings, dryRun);
            }
        }
    }
}
