package com.auraboot.framework.inbox.listener;

import com.auraboot.framework.inbox.model.InboxItem;
import com.auraboot.framework.inbox.service.InboxService;
import com.auraboot.framework.user.service.UserService;
import com.auraboot.module.meta.event.CommandCompletedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class InboxEventListenerTest {

    @Mock
    private InboxService inboxService;

    @Mock
    private UserService userService;

    @Test
    void createsReadableAssignmentTitleWithoutStateFallbackMarkers() {
        InboxEventListener listener = new InboxEventListener(inboxService, new ObjectMapper(), userService);
        CommandCompletedEvent event = buildStateTransitionEvent(
                "crm:activate_campaign",
                Map.of()
        );

        listener.onCommandCompleted(event);

        ArgumentCaptor<InboxItem> captor = ArgumentCaptor.forClass(InboxItem.class);
        verify(inboxService).createItem(captor.capture());
        InboxItem item = captor.getValue();

        assertThat(item.getItemType()).isEqualTo("assignment");
        assertThat(item.getTitle()).isEqualTo("Activate Campaign");
        assertThat(item.getSubtitle()).isEqualTo("Campaign #01KTESTCAMPAIGN");
        assertThat(item.getDeepLink()).isEqualTo("auraboot://object/crm_campaign/01KTESTCAMPAIGN");
        assertThat(item.getCardPayload()).contains("\"commandCode\":\"crm:activate_campaign\"");
    }

    @Test
    void createsStateTransitionTitleWhenFromAndToStatesExist() {
        InboxEventListener listener = new InboxEventListener(inboxService, new ObjectMapper(), userService);
        Map<String, Object> payload = new HashMap<>();
        payload.put("fromState", "draft");
        payload.put("toState", "active");
        CommandCompletedEvent event = buildStateTransitionEvent("crm:activate_campaign", payload);

        listener.onCommandCompleted(event);

        ArgumentCaptor<InboxItem> captor = ArgumentCaptor.forClass(InboxItem.class);
        verify(inboxService).createItem(captor.capture());
        InboxItem item = captor.getValue();

        assertThat(item.getTitle()).isEqualTo("Activate Campaign: Draft → Active");
        assertThat(item.getSubtitle()).isEqualTo("Campaign #01KTESTCAMPAIGN");
    }

    @Test
    void ignoresNonStateTransitionCommandEvents() {
        InboxEventListener listener = new InboxEventListener(inboxService, new ObjectMapper(), userService);
        CommandCompletedEvent event = new CommandCompletedEvent(
                100L,
                "01KTESTCAMPAIGN",
                "crm_campaign",
                Map.of(),
                "crm:update_campaign",
                "update"
        );
        event.addMetadata("actorId", 42L);

        listener.onCommandCompleted(event);

        verifyNoInteractions(inboxService);
    }

    private CommandCompletedEvent buildStateTransitionEvent(String commandCode, Map<String, Object> payload) {
        CommandCompletedEvent event = new CommandCompletedEvent(
                100L,
                "01KTESTCAMPAIGN",
                "crm_campaign",
                payload,
                commandCode,
                "state_transition"
        );
        event.addMetadata("actorId", 42L);
        event.addMetadata("actorName", "Alex");
        return event;
    }
}
