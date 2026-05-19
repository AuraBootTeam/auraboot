package com.auraboot.framework.crm.listener;

import com.auraboot.framework.crm.service.CrmPrimaryContactService;
import com.auraboot.module.meta.event.CommandCompletedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class CrmPrimaryContactListenerTest {

    @Mock
    private CrmPrimaryContactService crmPrimaryContactService;

    @Test
    void ignoresEventsWithoutOperationType() {
        CrmPrimaryContactListener listener = new CrmPrimaryContactListener(crmPrimaryContactService);
        CommandCompletedEvent event = new CommandCompletedEvent(
                100L,
                "01KCONTACT",
                "crm_contact",
                Map.of(),
                "crm:update_contact",
                null
        );

        assertThatCode(() -> listener.onCommandCompleted(event)).doesNotThrowAnyException();

        verifyNoInteractions(crmPrimaryContactService);
    }

    @Test
    void normalizesPrimaryContactForCreateAndUpdateEvents() {
        CrmPrimaryContactListener listener = new CrmPrimaryContactListener(crmPrimaryContactService);
        CommandCompletedEvent event = new CommandCompletedEvent(
                100L,
                "01KCONTACT",
                "crm_contact",
                Map.of(),
                "crm:update_contact",
                "update"
        );

        listener.onCommandCompleted(event);

        verify(crmPrimaryContactService).ensureSinglePrimaryContact(100L, "01KCONTACT");
    }

    @Test
    void ignoresNonCrmContactEvents() {
        CrmPrimaryContactListener listener = new CrmPrimaryContactListener(crmPrimaryContactService);
        CommandCompletedEvent event = new CommandCompletedEvent(
                100L,
                "01KACCOUNT",
                "crm_account",
                Map.of(),
                "crm:update_account",
                "update"
        );

        listener.onCommandCompleted(event);

        verifyNoInteractions(crmPrimaryContactService);
    }
}
