package com.auraboot.framework.scheduler.handler;

import com.auraboot.framework.scheduler.entity.ScheduledTask;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduledTaskSmokeHandlerTest {

    @Test
    void beanNameMatchesSchedulerAllowlist() {
        Component component = ScheduledTaskSmokeHandler.class.getAnnotation(Component.class);

        assertThat(component).isNotNull();
        assertThat(component.value()).isEqualTo("scheduledTaskSmokeHandler");
    }

    @Test
    void executeAcceptsScheduledTaskParameter() throws Exception {
        Method method = ScheduledTaskSmokeHandler.class.getMethod("execute", ScheduledTask.class);

        assertThat(method).isNotNull();
    }
}
