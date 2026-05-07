package com.auraboot.plugins.workflowdemo.handler;

import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension.CommandContext;
import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/**
 * Stub handler proving PF4J extension wiring for the workflow-demo plugin
 * backend module. The real AI fill / safety / annotation logic for the
 * P1' ACP platformization vertical slice lives in platform controllers /
 * services (faster path for PoC); this handler exists so the plugin
 * declares at least one extension and the validator does not flag the
 * backend as empty.
 *
 * Replace or expand once the platform-side logic stabilizes and we want
 * to push extensions back into the plugin.
 */
@Extension
public class WdLeaveAiFillStubHandler implements CommandHandlerExtension {

    private static final Logger log = LoggerFactory.getLogger(WdLeaveAiFillStubHandler.class);
    private static final String COMMAND = "wd:leave_ai_fill_noop";
    private static final Set<String> SUPPORTED = Set.of(COMMAND);

    @Override
    public String getCommandType() {
        return COMMAND;
    }

    @Override
    public boolean supports(String commandType) {
        return SUPPORTED.contains(commandType);
    }

    @Override
    public Object execute(CommandContext context) {
        log.debug("wd:leave_ai_fill_noop invoked for record {}", context.recordId());
        return null;
    }
}
