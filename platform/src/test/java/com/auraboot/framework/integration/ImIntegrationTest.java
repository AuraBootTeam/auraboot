package com.auraboot.framework.integration;

import com.auraboot.framework.im.dto.*;
import com.auraboot.framework.im.model.ImConstants;
import com.auraboot.framework.im.model.ImConversation;
import com.auraboot.framework.im.model.ImConversationMember;
import com.auraboot.framework.im.model.ImMessage;
import com.auraboot.framework.im.model.ImNotificationPreference;
import com.auraboot.framework.im.mapper.ImConversationMemberMapper;
import com.auraboot.framework.im.service.ImConversationService;
import com.auraboot.framework.im.service.ImMessageService;
import com.auraboot.framework.im.service.ImNotificationPreferenceService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IM module integration test.
 * Tests conversation CRUD, message send/sync, seq-based operations, and dedup.
 * Uses real database, no mocking. Data persists (no rollback).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class ImIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ImConversationService conversationService;

    @Autowired
    private ImMessageService messageService;

    @Autowired
    private ImConversationMemberMapper memberMapper;

    @Autowired
    private ImNotificationPreferenceService notificationPreferenceService;

    // Created in @BeforeAll-equivalent first test
    private Long groupConversationId;
    private Long initialMaxSeq;
    private final String testRunId = String.valueOf(System.currentTimeMillis());

    // Object conversation fields
    private Long objectConversationId;
    private final String testModelCode = "crm_opportunity";
    private final Long testRecordId = System.currentTimeMillis();

    // ========== Conversation Tests ==========

    @Test
    @Order(1)
    void createGroupConversation() {
        ConversationCreateRequest request = new ConversationCreateRequest();
        request.setType("group");
        request.setName("IM Test Group " + testRunId);
        request.setMemberIds(List.of(getTestUser().getId()));

        ImConversation conv = conversationService.create(
                request, getTestUser().getId(), getTestTenant().getId());

        assertNotNull(conv);
        assertNotNull(conv.getId());
        assertEquals("group", conv.getType());
        assertEquals(0L, conv.getMaxSeq());

        this.groupConversationId = conv.getId();
        this.initialMaxSeq = conv.getMaxSeq();
    }

    @Test
    @Order(2)
    void listConversations_containsNewGroup() {
        List<ConversationListItem> items = conversationService.listByUser(
                getTestUser().getId(), getTestTenant().getId());

        assertFalse(items.isEmpty());
        boolean foundGroup = items.stream()
                .anyMatch(i -> i.getConversationId().equals(groupConversationId));
        assertTrue(foundGroup, "Should find the group conversation we just created");
    }

    @Test
    @Order(3)
    void isMember_positive() {
        assertTrue(conversationService.isMember(
                groupConversationId, getTestUser().getId(), getTestTenant().getId()));
    }

    @Test
    @Order(4)
    void isMember_negative() {
        assertFalse(conversationService.isMember(
                groupConversationId, 999999L, getTestTenant().getId()));
    }

    @Test
    @Order(5)
    void getConversationById() {
        ImConversation conv = conversationService.getById(groupConversationId, getTestTenant().getId());
        assertNotNull(conv);
        assertEquals(groupConversationId, conv.getId());
        assertEquals("group", conv.getType());
    }

    @Test
    @Order(6)
    void createPrivateConversation_dedup() {
        // Create twice with same member — should return the same conversation
        ConversationCreateRequest request = new ConversationCreateRequest();
        request.setType("private");
        request.setMemberIds(List.of(getTestUser().getId()));

        ImConversation first = conversationService.create(
                request, getTestUser().getId(), getTestTenant().getId());
        ImConversation second = conversationService.create(
                request, getTestUser().getId(), getTestTenant().getId());

        assertEquals(first.getId(), second.getId(), "PRIVATE conversation dedup should return same conversation");
    }

    // ========== Message Tests ==========

    @Test
    @Order(10)
    void sendTextMessage() {
        SendMessageRequest request = new SendMessageRequest();
        request.setConversationId(groupConversationId);
        request.setMessageType("text");
        request.setContent("Hello, integration test " + testRunId);
        request.setClientMsgId("it_" + testRunId + "_001");

        ImMessage msg = messageService.sendMessage(
                request, getTestUser().getId(), getTestTenant().getId());

        assertNotNull(msg);
        assertNotNull(msg.getId());
        assertEquals(initialMaxSeq + 1, msg.getSeq(), "First message should increment seq by 1");
        assertEquals("text", msg.getMessageType());
        assertTrue(msg.getContent().contains(testRunId));
    }

    @Test
    @Order(11)
    void sendMessage_seqIncrements() {
        SendMessageRequest request = new SendMessageRequest();
        request.setConversationId(groupConversationId);
        request.setMessageType("text");
        request.setContent("Second message " + testRunId);
        request.setClientMsgId("it_" + testRunId + "_002");

        ImMessage msg = messageService.sendMessage(
                request, getTestUser().getId(), getTestTenant().getId());

        assertEquals(initialMaxSeq + 2, msg.getSeq(), "Second message should increment seq by 2");
    }

    @Test
    @Order(12)
    void sendMessage_dedup() {
        SendMessageRequest request = new SendMessageRequest();
        request.setConversationId(groupConversationId);
        request.setMessageType("text");
        request.setContent("This should be deduped");
        request.setClientMsgId("it_" + testRunId + "_001"); // same as first message

        ImMessage msg = messageService.sendMessage(
                request, getTestUser().getId(), getTestTenant().getId());

        assertEquals(initialMaxSeq + 1, msg.getSeq(), "Deduped message should return original seq");
        assertTrue(msg.getContent().contains("Hello"), "Should return original content");
    }

    @Test
    @Order(13)
    void sendCardMessage() {
        SendMessageRequest request = new SendMessageRequest();
        request.setConversationId(groupConversationId);
        request.setMessageType("card");
        request.setClientMsgId("it_" + testRunId + "_card_001");
        request.setCardPayload(java.util.Map.of(
                "cardType", "crm_object",
                "title", "Test Customer",
                "subtitle", "Acme Corp"
        ));

        ImMessage msg = messageService.sendMessage(
                request, getTestUser().getId(), getTestTenant().getId());

        assertEquals("card", msg.getMessageType());
        assertNotNull(msg.getCardPayload());
        assertTrue(msg.getCardPayload().contains("crm_object"));
    }

    @Test
    @Order(14)
    void sendMessage_nonMemberRejected() {
        SendMessageRequest request = new SendMessageRequest();
        request.setConversationId(groupConversationId);
        request.setMessageType("text");
        request.setContent("Should fail");
        request.setClientMsgId("it_" + testRunId + "_fail");

        assertThrows(IllegalArgumentException.class, () ->
                messageService.sendMessage(request, 999999L, getTestTenant().getId()));
    }

    // ========== Sync & History Tests ==========

    @Test
    @Order(20)
    void getMessagesAfterSeq() {
        List<ImMessage> messages = messageService.getMessagesAfterSeq(
                groupConversationId, initialMaxSeq, 50, getTestTenant().getId());

        assertTrue(messages.size() >= 2, "Should have at least 2 messages after initial seq");
        // Messages should be ordered by seq ASC
        for (int i = 1; i < messages.size(); i++) {
            assertTrue(messages.get(i).getSeq() > messages.get(i - 1).getSeq(),
                    "Messages should be ordered by seq ASC");
        }
    }

    @Test
    @Order(21)
    void getMessagesBeforeSeq() {
        List<ImMessage> messages = messageService.getMessagesBeforeSeq(
                groupConversationId, Long.MAX_VALUE, 50, getTestTenant().getId());

        assertFalse(messages.isEmpty());
        for (int i = 1; i < messages.size(); i++) {
            assertTrue(messages.get(i).getSeq() < messages.get(i - 1).getSeq(),
                    "Messages should be ordered by seq DESC for backward pagination");
        }
    }

    @Test
    @Order(22)
    void getMessagesAfterSeq_partialSync() {
        List<ImMessage> messages = messageService.getMessagesAfterSeq(
                groupConversationId, initialMaxSeq + 1, 50, getTestTenant().getId());

        assertTrue(messages.size() >= 1);
        assertTrue(messages.get(0).getSeq() > initialMaxSeq + 1, "First message seq should be > afterSeq");
    }

    // ========== Read Receipt Tests ==========

    @Test
    @Order(30)
    void markRead() {
        long readSeq = initialMaxSeq + 2;
        messageService.markRead(groupConversationId, getTestUser().getId(), readSeq, getTestTenant().getId());

        ImConversationMember member = memberMapper.findMember(
                groupConversationId, getTestUser().getId(), getTestTenant().getId());
        assertTrue(member.getLastReadSeq() >= readSeq, "last_read_seq should be at least " + readSeq);
    }

    @Test
    @Order(31)
    void markRead_neverGoesBackward() {
        ImConversationMember before = memberMapper.findMember(
                groupConversationId, getTestUser().getId(), getTestTenant().getId());
        long currentReadSeq = before.getLastReadSeq();

        // Try to set to a lower value
        messageService.markRead(groupConversationId, getTestUser().getId(), 0L, getTestTenant().getId());

        ImConversationMember after = memberMapper.findMember(
                groupConversationId, getTestUser().getId(), getTestTenant().getId());
        assertEquals(currentReadSeq, after.getLastReadSeq(), "last_read_seq should NOT decrease");
    }

    // ========== Unread Summary Tests ==========

    @Test
    @Order(40)
    void unreadSummary() {
        UnreadSummary summary = conversationService.getUnreadSummary(
                getTestUser().getId(), getTestTenant().getId());

        assertNotNull(summary);
        assertNotNull(summary.getConversations());
        assertTrue(summary.getTotalUnread() >= 0);
    }

    // ========== Conversation maxSeq verification ==========

    @Test
    @Order(50)
    void conversationMaxSeq_reflects_messages() {
        ImConversation conv = conversationService.getById(groupConversationId, getTestTenant().getId());
        assertNotNull(conv);
        assertTrue(conv.getMaxSeq() >= initialMaxSeq + 3,
                "max_seq should reflect at least 3 sent messages (text + text + card)");
    }

    // ========== BOT Conversation Tests ==========

    @Test
    @Order(60)
    void findOrCreateBotConversation_creates() {
        ImConversation bot = conversationService.findOrCreateBotConversation(
                getTestUser().getId(), getTestTenant().getId());

        assertNotNull(bot);
        assertNotNull(bot.getId());
        assertEquals("bot", bot.getType());

        // User should be a member
        assertTrue(conversationService.isMember(
                bot.getId(), getTestUser().getId(), getTestTenant().getId()));
    }

    @Test
    @Order(61)
    void findOrCreateBotConversation_dedup() {
        ImConversation first = conversationService.findOrCreateBotConversation(
                getTestUser().getId(), getTestTenant().getId());
        ImConversation second = conversationService.findOrCreateBotConversation(
                getTestUser().getId(), getTestTenant().getId());

        assertEquals(first.getId(), second.getId(),
                "Same user should get the same BOT conversation");
    }

    // ========== System Message Tests ==========

    @Test
    @Order(70)
    void sendSystemMessage() {
        ImConversation bot = conversationService.findOrCreateBotConversation(
                getTestUser().getId(), getTestTenant().getId());

        String cardPayload = "{\"cardType\":\"command_completed\",\"modelCode\":\"test_model\"}";
        ImMessage msg = messageService.sendSystemMessage(
                bot.getId(), getTestTenant().getId(),
                "card", "Test system notification", cardPayload,
                "sys_" + testRunId + "_001");

        assertNotNull(msg);
        assertEquals(0L, msg.getSenderId(), "System message senderId should be 0");
        assertEquals("card", msg.getMessageType());
        assertNotNull(msg.getCardPayload());
        assertTrue(msg.getCardPayload().contains("command_completed"));
        assertTrue(msg.getSeq() > 0);
    }

    @Test
    @Order(71)
    void sendSystemMessage_dedup() {
        ImConversation bot = conversationService.findOrCreateBotConversation(
                getTestUser().getId(), getTestTenant().getId());

        ImMessage first = messageService.sendSystemMessage(
                bot.getId(), getTestTenant().getId(),
                "card", "First", "{}", "sys_" + testRunId + "_dedup");
        ImMessage second = messageService.sendSystemMessage(
                bot.getId(), getTestTenant().getId(),
                "card", "Second", "{}", "sys_" + testRunId + "_dedup");

        assertEquals(first.getId(), second.getId(), "Deduped system message should return same id");
        assertEquals(first.getSeq(), second.getSeq(), "Deduped system message should return same seq");
    }

    @Test
    @Order(72)
    void sendSystemMessage_appearsInConversationList() {
        List<ConversationListItem> items = conversationService.listByUser(
                getTestUser().getId(), getTestTenant().getId());

        boolean foundBot = items.stream()
                .anyMatch(i -> "bot".equals(i.getType()));
        assertTrue(foundBot, "BOT conversation should appear in user's conversation list");
    }

    // ========== Message Recall Tests ==========

    @Test
    @Order(80)
    void recallMessage_success() {
        // Send a message to recall
        SendMessageRequest request = new SendMessageRequest();
        request.setConversationId(groupConversationId);
        request.setMessageType("text");
        request.setContent("This will be recalled " + testRunId);
        request.setClientMsgId("it_" + testRunId + "_recall_001");

        ImMessage sent = messageService.sendMessage(
                request, getTestUser().getId(), getTestTenant().getId());
        assertFalse(sent.getRecalled());

        // Recall it
        ImMessage recalled = messageService.recallMessage(
                sent.getId(), getTestUser().getId(), getTestTenant().getId());

        assertTrue(recalled.getRecalled(), "Message should be marked as recalled");
        assertNull(recalled.getContent(), "Recalled message content should be cleared");
    }

    @Test
    @Order(81)
    void recallMessage_nonSenderRejected() {
        // Send a message
        SendMessageRequest request = new SendMessageRequest();
        request.setConversationId(groupConversationId);
        request.setMessageType("text");
        request.setContent("Cannot recall by others " + testRunId);
        request.setClientMsgId("it_" + testRunId + "_recall_002");

        ImMessage sent = messageService.sendMessage(
                request, getTestUser().getId(), getTestTenant().getId());

        // Try to recall as a different user
        assertThrows(IllegalArgumentException.class, () ->
                messageService.recallMessage(sent.getId(), 999999L, getTestTenant().getId()));
    }

    @Test
    @Order(82)
    void recallMessage_alreadyRecalledRejected() {
        // Send and recall
        SendMessageRequest request = new SendMessageRequest();
        request.setConversationId(groupConversationId);
        request.setMessageType("text");
        request.setContent("Double recall test " + testRunId);
        request.setClientMsgId("it_" + testRunId + "_recall_003");

        ImMessage sent = messageService.sendMessage(
                request, getTestUser().getId(), getTestTenant().getId());
        messageService.recallMessage(sent.getId(), getTestUser().getId(), getTestTenant().getId());

        // Try to recall again
        assertThrows(IllegalArgumentException.class, () ->
                messageService.recallMessage(sent.getId(), getTestUser().getId(), getTestTenant().getId()));
    }

    // ========== Mention Tests ==========

    @Test
    @Order(90)
    void sendMessageWithMentions() {
        SendMessageRequest request = new SendMessageRequest();
        request.setConversationId(groupConversationId);
        request.setMessageType("text");
        request.setContent("Hey @user check this " + testRunId);
        request.setClientMsgId("it_" + testRunId + "_mention_001");
        request.setMentions(List.of(String.valueOf(getTestUser().getId())));

        ImMessage msg = messageService.sendMessage(
                request, getTestUser().getId(), getTestTenant().getId());

        assertNotNull(msg.getMentions());
        assertTrue(msg.getMentions().contains(String.valueOf(getTestUser().getId())));
    }

    // ========== File Message Tests ==========

    @Test
    @Order(91)
    void sendFileMessage() {
        SendMessageRequest request = new SendMessageRequest();
        request.setConversationId(groupConversationId);
        request.setMessageType("file");
        request.setContent("design-spec.pdf");
        request.setClientMsgId("it_" + testRunId + "_file_001");
        request.setAttachments(List.of(java.util.Map.of(
                "type", "file",
                "fileName", "design-spec.pdf",
                "fileSize", 1024000,
                "mimeType", "application/pdf",
                "url", "/uploads/design-spec.pdf"
        )));

        ImMessage msg = messageService.sendMessage(
                request, getTestUser().getId(), getTestTenant().getId());

        assertEquals("file", msg.getMessageType());
        assertNotNull(msg.getAttachments());
        assertTrue(msg.getAttachments().contains("design-spec.pdf"));
    }

    // ========== Notification Preference Tests ==========

    @Test
    @Order(100)
    void notificationPreference_defaultEnabled() {
        boolean enabled = notificationPreferenceService.isEnabled(
                getTestUser().getId(), getTestTenant().getId(),
                "test_model", "state_transition");
        assertTrue(enabled, "Default should be enabled when no preference exists");
    }

    @Test
    @Order(101)
    void notificationPreference_disableGlobal() {
        // Disable all notifications globally
        ImNotificationPreference pref = notificationPreferenceService.setPreference(
                getTestUser().getId(), getTestTenant().getId(),
                null, null, false);
        assertNotNull(pref.getId());
        assertFalse(pref.getEnabled());

        boolean enabled = notificationPreferenceService.isEnabled(
                getTestUser().getId(), getTestTenant().getId(),
                "any_model", "state_transition");
        assertFalse(enabled, "Should be disabled after global disable");

        // Re-enable
        notificationPreferenceService.setPreference(
                getTestUser().getId(), getTestTenant().getId(),
                null, null, true);
    }

    @Test
    @Order(102)
    void notificationPreference_specificModelOverride() {
        // Disable specific model
        notificationPreferenceService.setPreference(
                getTestUser().getId(), getTestTenant().getId(),
                "test_disabled_model", null, false);

        boolean disabledModel = notificationPreferenceService.isEnabled(
                getTestUser().getId(), getTestTenant().getId(),
                "test_disabled_model", "state_transition");
        assertFalse(disabledModel, "Specific model should be disabled");

        boolean otherModel = notificationPreferenceService.isEnabled(
                getTestUser().getId(), getTestTenant().getId(),
                "other_model", "state_transition");
        assertTrue(otherModel, "Other models should still be enabled");
    }

    @Test
    @Order(103)
    void notificationPreference_listAndDelete() {
        List<ImNotificationPreference> prefs = notificationPreferenceService.listByUser(
                getTestUser().getId(), getTestTenant().getId());
        assertFalse(prefs.isEmpty(), "Should have preferences from previous tests");

        // Delete specific model preference
        ImNotificationPreference modelPref = prefs.stream()
                .filter(p -> "test_disabled_model".equals(p.getModelCode()))
                .findFirst().orElse(null);
        if (modelPref != null) {
            notificationPreferenceService.deletePreference(
                    modelPref.getId(), getTestUser().getId(), getTestTenant().getId());
        }

        // After deletion, model should be enabled again
        boolean enabled = notificationPreferenceService.isEnabled(
                getTestUser().getId(), getTestTenant().getId(),
                "test_disabled_model", "state_transition");
        assertTrue(enabled, "Should revert to default (enabled) after preference deleted");
    }

    @Test
    @Order(104)
    void notificationPreference_upsert() {
        // Set preference
        ImNotificationPreference first = notificationPreferenceService.setPreference(
                getTestUser().getId(), getTestTenant().getId(),
                "upsert_model", "custom", false);

        // Update same preference
        ImNotificationPreference second = notificationPreferenceService.setPreference(
                getTestUser().getId(), getTestTenant().getId(),
                "upsert_model", "custom", true);

        assertEquals(first.getId(), second.getId(), "Upsert should update same record");
        assertTrue(second.getEnabled());

        // Cleanup
        notificationPreferenceService.deletePreference(
                second.getId(), getTestUser().getId(), getTestTenant().getId());
    }

    // ========== Object Conversation Tests ==========

    @Test
    @Order(110)
    void createObjectConversation() {
        ConversationCreateRequest request = new ConversationCreateRequest();
        request.setType(ImConstants.TYPE_OBJECT);
        request.setName("Test Object Conv " + testRunId);
        request.setBoundModelCode(testModelCode);
        request.setBoundRecordId(testRecordId);

        ImConversation conv = conversationService.create(
                request, getTestUser().getId(), getTestTenant().getId());

        assertNotNull(conv);
        assertNotNull(conv.getId());
        assertEquals(ImConstants.TYPE_OBJECT, conv.getType());
        assertEquals(testModelCode, conv.getBoundModelCode());
        assertEquals(testRecordId, conv.getBoundRecordId());

        this.objectConversationId = conv.getId();
    }

    @Test
    @Order(111)
    void createObjectConversation_dedup() {
        ConversationCreateRequest request = new ConversationCreateRequest();
        request.setType(ImConstants.TYPE_OBJECT);
        request.setBoundModelCode(testModelCode);
        request.setBoundRecordId(testRecordId);

        ImConversation conv = conversationService.create(
                request, getTestUser().getId(), getTestTenant().getId());

        assertEquals(objectConversationId, conv.getId(),
                "Creating object conv for same record should return existing");
    }

    @Test
    @Order(112)
    void createObjectConversation_requiresBoundFields() {
        ConversationCreateRequest request = new ConversationCreateRequest();
        request.setType(ImConstants.TYPE_OBJECT);

        assertThrows(IllegalArgumentException.class, () ->
            conversationService.create(request, getTestUser().getId(), getTestTenant().getId()));
    }

    @Test
    @Order(113)
    void findByBoundRecord_found() {
        ImConversation conv = conversationService.findByBoundRecord(
                testModelCode, testRecordId, getTestTenant().getId());

        assertNotNull(conv);
        assertEquals(objectConversationId, conv.getId());
        assertEquals(testModelCode, conv.getBoundModelCode());
    }

    @Test
    @Order(114)
    void findByBoundRecord_notFound() {
        ImConversation conv = conversationService.findByBoundRecord(
                "nonexistent_model", 999999L, getTestTenant().getId());

        assertNull(conv);
    }

    @Test
    @Order(115)
    void findByBoundRecord_crossTenant() {
        Long otherTenantId = getTestTenant().getId() + 99999;
        ImConversation conv = conversationService.findByBoundRecord(
                testModelCode, testRecordId, otherTenantId);

        assertNull(conv, "Should not find conversation from another tenant");
    }

    @Test
    @Order(116)
    void objectConversation_messaging() {
        SendMessageRequest request = new SendMessageRequest();
        request.setConversationId(objectConversationId);
        request.setMessageType("text");
        request.setContent("Discussion about this record");
        request.setClientMsgId("obj_msg_" + testRunId);

        ImMessage msg = messageService.sendMessage(
                request, getTestUser().getId(), getTestTenant().getId());

        assertNotNull(msg);
        assertEquals(1L, msg.getSeq());
        assertEquals(objectConversationId, msg.getConversationId());
    }

    @Test
    @Order(117)
    void objectConversation_appearsInListWithTypeFilter() {
        List<ConversationListItem> allItems = conversationService.listByUser(
                getTestUser().getId(), getTestTenant().getId());
        boolean foundObject = allItems.stream()
                .anyMatch(i -> ImConstants.TYPE_OBJECT.equals(i.getType()));
        assertTrue(foundObject, "Object conversation should appear in full list");

        List<ConversationListItem> objectOnly = conversationService.listByUser(
                getTestUser().getId(), getTestTenant().getId(), ImConstants.TYPE_OBJECT);
        assertFalse(objectOnly.isEmpty());
        assertTrue(objectOnly.stream().allMatch(i -> ImConstants.TYPE_OBJECT.equals(i.getType())),
                "Type filter should return only object conversations");
    }

    @Test
    @Order(120)
    void eventListener_dualDelivery_objectConvReceivesCard() {
        // The object conversation created in Order(110) should be findable
        ImConversation objConv = conversationService.findByBoundRecord(
                testModelCode, testRecordId, getTestTenant().getId());
        assertNotNull(objConv, "Object conversation should exist for dual-delivery");

        // Simulate what ImEventListener does: send a system card message to the object conversation
        String cardPayload = "{\"cardType\":\"command_completed\",\"modelCode\":\""
                + testModelCode + "\",\"recordId\":\"" + testRecordId + "\"}";
        String clientMsgId = "evt_obj_test_" + testRunId;

        ImMessage cardMsg = messageService.sendSystemMessage(
                objConv.getId(), getTestTenant().getId(),
                "card", "Test event notification", cardPayload, clientMsgId);

        assertNotNull(cardMsg);
        assertEquals("card", cardMsg.getMessageType());
        assertNotNull(cardMsg.getCardPayload());
        assertTrue(cardMsg.getCardPayload().contains("command_completed"));
    }

    @Test
    @Order(92)
    void sendImageMessage() {
        SendMessageRequest request = new SendMessageRequest();
        request.setConversationId(groupConversationId);
        request.setMessageType("image");
        request.setContent("screenshot.png");
        request.setClientMsgId("it_" + testRunId + "_img_001");
        request.setAttachments(List.of(java.util.Map.of(
                "type", "image",
                "fileName", "screenshot.png",
                "mimeType", "image/png",
                "url", "/uploads/screenshot.png",
                "width", 1920,
                "height", 1080
        )));

        ImMessage msg = messageService.sendMessage(
                request, getTestUser().getId(), getTestTenant().getId());

        assertEquals("image", msg.getMessageType());
        assertNotNull(msg.getAttachments());
        assertTrue(msg.getAttachments().contains("screenshot.png"));
    }
}
