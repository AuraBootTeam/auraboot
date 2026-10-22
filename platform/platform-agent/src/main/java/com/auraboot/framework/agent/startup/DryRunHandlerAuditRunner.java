package com.auraboot.framework.agent.startup;

import com.auraboot.framework.meta.service.CommandHandler;
import com.auraboot.framework.meta.service.DryRunSafe;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.pf4j.ExtensionRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Startup audit for dry-run handler coverage.
 *
 * <p>Walks every {@link CommandHandler} Spring bean and every registered
 * plugin {@link CommandHandlerExtension}, logging which ones are eligible
 * to run under {@code CommandExecuteRequest.dryRun=true} and which ones
 * will be skipped.
 *
 * <p>Output is purely advisory — it lets operators see at boot time which
 * handlers short-circuit under dry-run (good: safe for preview/approval
 * flows) and which silently do nothing (surprise: plugin author may have
 * forgotten to opt in).
 *
 * <p>Gated behind {@code acp.learning.dry-run.audit.enabled} (default
 * {@code true}); tests can disable via {@code =false} to avoid log noise.
 *
 * @since PR-59
 */
@Slf4j
@Component
public class DryRunHandlerAuditRunner implements ApplicationRunner {

    private final ApplicationContext applicationContext;
    private final ExtensionRegistry extensionRegistry;
    private final boolean enabled;

    public DryRunHandlerAuditRunner(ApplicationContext applicationContext,
                                    ExtensionRegistry extensionRegistry,
                                    @Value("${acp.learning.dry-run.audit.enabled:true}") boolean enabled) {
        this.applicationContext = applicationContext;
        this.extensionRegistry = extensionRegistry;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.debug("DryRunHandlerAuditRunner: disabled by acp.learning.dry-run.audit.enabled=false");
            return;
        }

        // --- Spring bean CommandHandlers ---
        Map<String, CommandHandler> beans = applicationContext.getBeansOfType(CommandHandler.class);
        List<String> safeBeans = new ArrayList<>();
        List<String> unsafeBeans = new ArrayList<>();
        for (Map.Entry<String, CommandHandler> entry : beans.entrySet()) {
            Class<?> cls = entry.getValue().getClass();
            String fqn = cls.getName();
            if (cls.isAnnotationPresent(DryRunSafe.class)) {
                safeBeans.add(fqn);
            } else {
                unsafeBeans.add(fqn);
            }
        }
        Collections.sort(safeBeans);
        Collections.sort(unsafeBeans);
        for (String fqn : safeBeans) {
            log.info("DryRunAudit: CommandHandler {} is @DryRunSafe — will execute under dry-run", fqn);
        }
        for (String fqn : unsafeBeans) {
            log.warn("DryRunAudit: CommandHandler {} is NOT @DryRunSafe — will be SKIPPED under dry-run", fqn);
        }

        // --- Plugin CommandHandlerExtensions ---
        List<CommandHandlerExtension> plugins = extensionRegistry == null
                ? List.of()
                : extensionRegistry.getAllCommandHandlers();
        List<String> safePlugins = new ArrayList<>();
        List<String> unsafePlugins = new ArrayList<>();
        for (CommandHandlerExtension p : plugins) {
            String label = p.getClass().getName() + " (commandType=" + p.getCommandType() + ")";
            if (p.supportsDryRun()) {
                safePlugins.add(label);
            } else {
                unsafePlugins.add(label);
            }
        }
        Collections.sort(safePlugins);
        Collections.sort(unsafePlugins);
        for (String label : safePlugins) {
            log.info("DryRunAudit: plugin handler {} supportsDryRun=true — will execute under dry-run", label);
        }
        for (String label : unsafePlugins) {
            log.warn("DryRunAudit: plugin handler {} supportsDryRun=false — will be SKIPPED under dry-run", label);
        }

        log.info("DryRunAudit summary: {} safe beans, {} unsafe beans; {} safe plugins, {} unsafe plugins",
                safeBeans.size(), unsafeBeans.size(), safePlugins.size(), unsafePlugins.size());
    }
}
