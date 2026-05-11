package com.auraboot.framework.inbox.dto;

import com.auraboot.framework.inbox.dto.InboxItemResponse.CardActionStyle;
import com.auraboot.framework.inbox.model.InboxItem;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks the {@code cardData.actions[]} contract for inbox cards.
 *
 * <p>Mobile and web clients depend on this shape:
 * <pre>
 *   "actions": [
 *     { "action": "approve", "label": "Approve", "style": "primary" },
 *     { "action": "reject",  "label": "Reject",  "style": "destructive" }
 *   ]
 * </pre>
 *
 * <p>If this test fails, mobile/web inbox cards will silently drop or
 * mis-render quick-action buttons. Producers (workflow engine, inbox
 * listeners, plugin event handlers) MUST emit this shape.
 */
class InboxItemResponseCardActionsContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void cardActionStyle_wireValuesAreLowercase() {
        assertEquals("primary", CardActionStyle.PRIMARY.wireValue());
        assertEquals("secondary", CardActionStyle.SECONDARY.wireValue());
        assertEquals("destructive", CardActionStyle.DESTRUCTIVE.wireValue());
    }

    @Test
    void cardActionStyle_hasExactlyThreeValues() {
        // The contract enumerates exactly three styles. Adding a new style
        // requires updating Android InboxCardAction.Style, iOS CardAction.Style,
        // web ActionStyle, and the InboxItemResponse Javadoc — fail loudly here
        // so the change is intentional.
        assertEquals(3, CardActionStyle.values().length);
    }

    @Test
    void cardData_actionsArrayDeserializesIntoExpectedShape() throws Exception {
        String payload = """
            {
              "vendor": "Acme",
              "amount": 12345,
              "actions": [
                { "action": "approve", "label": "Approve", "style": "primary" },
                { "action": "reject",  "label": "Reject",  "style": "destructive" },
                { "action": "follow_up", "label": "Follow up" }
              ]
            }
            """;
        InboxItem item = new InboxItem();
        item.setId(1L);
        item.setItemType("approval");
        item.setTitle("PO #100");
        item.setPriority("HIGH");
        item.setStatus("PENDING");
        item.setCardPayload(payload);

        InboxItemResponse response = InboxItemResponse.from(item);

        assertNotNull(response.getCardData(), "cardData must be parsed when cardPayload is valid JSON");
        Object actionsRaw = response.getCardData().get("actions");
        assertNotNull(actionsRaw, "cardData.actions must be present");
        assertTrue(actionsRaw instanceof List<?>, "cardData.actions must deserialise as a list");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> actions = MAPPER.convertValue(
                actionsRaw, new TypeReference<>() {});

        assertEquals(3, actions.size());

        Map<String, Object> first = actions.get(0);
        assertEquals("approve", first.get("action"));
        assertEquals("Approve", first.get("label"));
        assertEquals("primary", first.get("style"));

        Map<String, Object> second = actions.get(1);
        assertEquals("destructive", second.get("style"));

        // style is optional — entry without style must still round-trip
        Map<String, Object> third = actions.get(2);
        assertEquals("follow_up", third.get("action"));
        assertEquals("Follow up", third.get("label"));
        assertNull(third.get("style"),
                "Missing style must remain null on the wire — clients default to SECONDARY");
    }

    @Test
    void responseExposesSourceRecordPidFromCardPayloadWhenLegacyRecordIdIsMissing() {
        InboxItem item = new InboxItem();
        item.setId(1L);
        item.setModelCode("crm_campaign");
        item.setCardPayload("""
            {
              "modelCode": "crm_campaign",
              "sourceRecordPid": "01KTESTCAMPAIGN",
              "recordPid": "01KTESTCAMPAIGN",
              "recordId": "01KTESTCAMPAIGN"
            }
            """);

        InboxItemResponse response = InboxItemResponse.from(item);

        assertEquals("crm_campaign", response.getSourceModel());
        assertNull(response.getRecordId(), "legacy numeric recordId must stay null for pid-only rows");
        assertEquals("01KTESTCAMPAIGN", response.getSourceRecordPid());
        assertEquals("01KTESTCAMPAIGN", response.getSourceRecordId());
    }

    @Test
    void cardData_isNullWhenCardPayloadIsBlank() {
        InboxItem item = new InboxItem();
        item.setId(1L);
        item.setCardPayload("");
        assertNull(InboxItemResponse.from(item).getCardData());
    }

    @Test
    void cardData_isNullWhenCardPayloadIsInvalidJson() {
        InboxItem item = new InboxItem();
        item.setId(1L);
        item.setCardPayload("{not json");
        assertNull(InboxItemResponse.from(item).getCardData(),
                "Invalid JSON in cardPayload must not crash the response — return null");
    }

    @Test
    void cardData_styleEnumWireValuesAreContractStable() {
        // If a value here changes, every mobile renderer breaks. This test
        // pins the wire format independently of the Java enum names.
        assertEquals("primary", CardActionStyle.PRIMARY.wireValue());
        assertEquals("secondary", CardActionStyle.SECONDARY.wireValue());
        assertEquals("destructive", CardActionStyle.DESTRUCTIVE.wireValue());
    }
}
