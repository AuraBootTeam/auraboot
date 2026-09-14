package com.auraboot.framework.inbox.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.auraboot.framework.inbox.mapper.InboxItemMapper;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InboxCleanupTaskTest {

    @Mock
    private InboxItemMapper inboxItemMapper;

    @InjectMocks
    private InboxCleanupTask task;

    @Test
    void cleanupExpiredExecutesBothGlobalRetentionOperations() {
        when(inboxItemMapper.markExpiredItems()).thenReturn(2);
        when(inboxItemMapper.deleteOldItems(90)).thenReturn(3);

        task.cleanupExpired();

        verify(inboxItemMapper).markExpiredItems();
        verify(inboxItemMapper).deleteOldItems(90);
    }

    @Test
    void schedulerOperationsExplicitlyBypassRequestTenantInterceptor() throws Exception {
        assertGlobalMaintenanceMethod(InboxItemMapper.class.getMethod("markExpiredItems"));
        assertGlobalMaintenanceMethod(InboxItemMapper.class.getMethod("deleteOldItems", int.class));
    }

    private void assertGlobalMaintenanceMethod(Method method) {
        InterceptorIgnore annotation = method.getAnnotation(InterceptorIgnore.class);
        assertThat(annotation)
                .as("%s must declare its cross-tenant scheduler boundary", method.getName())
                .isNotNull();
        assertThat(annotation.tenantLine()).isEqualTo("true");
    }
}
