package com.auraboot.framework.automation.workflow;

import com.auraboot.framework.automation.entity.AutomationNodeExecution;
import com.auraboot.framework.automation.mapper.AutomationNodeExecutionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Node audit facts survive action rollback; audit failures cannot abort business writes. */
@Service
@RequiredArgsConstructor
public class AutomationNodeExecutionRecorder {
    private final AutomationNodeExecutionMapper mapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long start(AutomationNodeExecution row) {
        mapper.insert(row);
        return row.getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(AutomationNodeExecution update) {
        mapper.updateById(update);
    }
}
