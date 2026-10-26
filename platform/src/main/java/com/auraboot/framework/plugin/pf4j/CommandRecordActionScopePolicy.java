package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.permission.engine.evaluator.DataScopeEvaluator;
import com.auraboot.framework.permission.engine.model.EvaluationVerdict;
import com.auraboot.framework.permission.service.RecordShareService;
import org.springframework.context.ApplicationContext;

import java.util.Map;

/** Row restriction only; the caller must separately prove business capability and readability. */
public final class CommandRecordActionScopePolicy {
    private CommandRecordActionScopePolicy() {}

    public static boolean permits(ApplicationContext beans, Long tenant, Long member, String user,
                                  String model, String pid, String action, Map<String,Object> record) {
        var decision = beans.getBean(DataScopeEvaluator.class).evaluate(member, model, action, record);
        if (decision == null || decision.verdict() == null) return false;
        if (decision.verdict() != EvaluationVerdict.DENY) return true;
        return beans.getBean(RecordShareService.class).isSharedByPid(tenant, model, pid, member, user, action);
    }
}
