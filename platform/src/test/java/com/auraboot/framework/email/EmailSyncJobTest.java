package com.auraboot.framework.email;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.email.job.EmailSyncJob;
import com.auraboot.framework.email.mapper.EmailAccountMapper;
import com.auraboot.framework.email.model.EmailAccount;
import com.auraboot.framework.email.service.EmailSyncService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("EmailSyncJob")
class EmailSyncJobTest {

    @Mock private EmailSyncService emailSyncService;
    @Mock private EmailAccountMapper emailAccountMapper;

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    @DisplayName("No active accounts -> no sync work")
    void noAccounts() {
        when(emailAccountMapper.findAllActiveGlobal()).thenReturn(List.of());

        new EmailSyncJob(emailSyncService, emailAccountMapper).syncAllAccounts();

        verify(emailSyncService, never()).syncAccount(org.mockito.ArgumentMatchers.any());
        assertFalse(MetaContext.exists());
    }

    @Test
    @DisplayName("Binds account tenant context during sync and clears it afterwards")
    void bindsTenantContextForAccount() throws Exception {
        EmailAccount account = new EmailAccount();
        account.setId(20L);
        account.setTenantId(88L);
        when(emailAccountMapper.findAllActiveGlobal()).thenReturn(List.of(account));
        org.mockito.Mockito.doAnswer(invocation -> {
            assertEquals(88L, MetaContext.getCurrentTenantId());
            return null;
        }).when(emailSyncService).syncAccount(account);

        new EmailSyncJob(emailSyncService, emailAccountMapper).syncAllAccounts();

        assertFalse(MetaContext.exists());
        verify(emailSyncService).syncAccount(account);
    }
}
