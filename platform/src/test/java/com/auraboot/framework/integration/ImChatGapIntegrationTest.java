package com.auraboot.framework.integration;

import com.auraboot.framework.im.dto.ConversationCreateRequest;
import com.auraboot.framework.im.dto.ConversationListItem;
import com.auraboot.framework.im.dto.ConversationUpdateRequest;
import com.auraboot.framework.im.dto.MessageSearchResult;
import com.auraboot.framework.im.dto.SendMessageRequest;
import com.auraboot.framework.im.mapper.ImConversationMemberMapper;
import com.auraboot.framework.im.model.ImConstants;
import com.auraboot.framework.im.model.ImConversation;
import com.auraboot.framework.im.model.ImMessage;
import com.auraboot.framework.im.service.ImConversationService;
import com.auraboot.framework.im.service.ImMessageService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for IM Chat Gap fixes (Tasks 1-4):
 * - Group Management: dissolve, leave, rename
 * - Conversation hiding
 * - Message recall (with 2-minute time limit)
 * - Message search
 * - Message forwarding
 *
 * Uses real PostgreSQL — no H2, no mocks for DB/Redis.
 * Uses NOT_SUPPORTED propagation so service @Transactional methods commit
 * independently, matching real runtime behavior.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class ImChatGapIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ImConversationService conversationService;

    @Autowired
    private ImMessageService messageService;

    @Autowired
    private ImConversationMemberMapper memberMapper;

    @Autowired
    private UserService userService;

    private final String ts = String.valueOf(System.currentTimeMillis());

    // Additional test users (created once)
    private User user2;
    private User user3;
    private User outsiderUser;

    private Long tenantId() {
        return getTestTenant().getId();
    }

    private Long userId() {
        return getTestUser().getId();
    }

    @BeforeAll
    void setupAdditionalUsers() {
        user2 = findOrCreateUser("im-gap-user2-" + ts + "@test.com");
        user3 = findOrCreateUser("im-gap-user3-" + ts + "@test.com");
        outsiderUser = findOrCreateUser("im-gap-outsider-" + ts + "@test.com");
    }

    private User findOrCreateUser(String email) {
        User existing = userService.findByEmail(email);
        if (existing != null) return existing;
        return userService.signUp(email, "test-password-123");
    }

    private ImConversation createGroup(String name, Long ownerId, List<Long> memberIds) {
        ConversationCreateRequest req = new ConversationCreateRequest();
        req.setType(ImConstants.TYPE_GROUP);
        req.setName(name);
        req.setMemberIds(memberIds);
        return conversationService.create(req, ownerId, tenantId());
    }

    private ImConversation createPrivateConversation(Long user1Id, Long user2Id) {
        ConversationCreateRequest req = new ConversationCreateRequest();
        req.setType(ImConstants.TYPE_PRIVATE);
        req.setMemberIds(List.of(user2Id));
        return conversationService.create(req, user1Id, tenantId());
    }

    private ImMessage sendTextMessage(Long conversationId, Long senderId, String content) {
        SendMessageRequest req = new SendMessageRequest();
        req.setConversationId(conversationId);
        req.setMessageType("text");
        req.setContent(content);
        req.setClientMsgId("cmid-" + ts + "-" + System.nanoTime());
        return messageService.sendMessage(req, senderId, tenantId());
    }

    // ========== Group Dissolve Tests ==========

    @Test
    @Order(1)
    void dissolveGroup_ownerCanDissolve() {
        ImConversation group = createGroup(
                "Dissolve Test " + ts, userId(), List.of(user2.getId(), user3.getId()));
        Long groupId = group.getId();

        // Send a message so there is data to delete
        sendTextMessage(groupId, userId(), "Hello before dissolve");

        // Dissolve as owner
        List<Long> memberIds = conversationService.dissolveGroup(groupId, userId(), tenantId());

        // Verify returned memberIds contains all 3 original members
        assertThat(memberIds).hasSize(3)
                .contains(userId(), user2.getId(), user3.getId());

        // Verify conversation deleted
        ImConversation deleted = conversationService.getById(groupId, tenantId());
        assertThat(deleted).isNull();

        // Verify members deleted
        List<Long> remainingMembers = memberMapper.findMemberIds(groupId, tenantId());
        assertThat(remainingMembers).isEmpty();

        // Verify messages deleted
        List<ImMessage> remainingMessages = messageService.getMessagesAfterSeq(groupId, 0L, 100, tenantId());
        assertThat(remainingMessages).isEmpty();
    }

    @Test
    @Order(2)
    void dissolveGroup_nonOwnerThrowsException() {
        ImConversation group = createGroup(
                "NonOwner Dissolve " + ts, userId(), List.of(user2.getId()));

        assertThatThrownBy(() ->
                conversationService.dissolveGroup(group.getId(), user2.getId(), tenantId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("owner");
    }

    @Test
    @Order(3)
    void dissolveGroup_cannotDissolvePrivateConversation() {
        ImConversation priv = createPrivateConversation(userId(), user2.getId());

        assertThatThrownBy(() ->
                conversationService.dissolveGroup(priv.getId(), userId(), tenantId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("group");
    }

    // ========== Group Leave Tests ==========

    @Test
    @Order(10)
    void leaveGroup_memberCanLeave() {
        ImConversation group = createGroup(
                "Leave Test " + ts, userId(), List.of(user2.getId(), user3.getId()));
        Long groupId = group.getId();

        // user2 (non-owner) leaves
        conversationService.leaveGroup(groupId, user2.getId(), tenantId());

        // Verify member removed
        assertThat(conversationService.isMember(groupId, user2.getId(), tenantId())).isFalse();

        // Verify owner and user3 still members
        assertThat(conversationService.isMember(groupId, userId(), tenantId())).isTrue();
        assertThat(conversationService.isMember(groupId, user3.getId(), tenantId())).isTrue();

        // Verify system message was sent (last message should contain "left the group")
        List<ImMessage> messages = messageService.getMessagesAfterSeq(groupId, 0L, 100, tenantId());
        assertThat(messages).isNotEmpty();
        ImMessage lastMsg = messages.get(messages.size() - 1);
        assertThat(lastMsg.getContent()).contains("left the group");
        assertThat(lastMsg.getSenderId()).isEqualTo(0L); // system sender
    }

    @Test
    @Order(11)
    void leaveGroup_ownerCannotLeave() {
        ImConversation group = createGroup(
                "Owner Leave " + ts, userId(), List.of(user2.getId()));

        assertThatThrownBy(() ->
                conversationService.leaveGroup(group.getId(), userId(), tenantId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("owner");
    }

    // ========== Group Rename Tests ==========

    @Test
    @Order(20)
    void updateConversation_renameGroup() {
        ImConversation group = createGroup(
                "Before Rename " + ts, userId(), List.of(user2.getId()));
        Long groupId = group.getId();

        String newName = "After Rename " + ts;
        ConversationUpdateRequest updateReq = new ConversationUpdateRequest();
        updateReq.setName(newName);
        conversationService.updateConversation(groupId, updateReq, userId(), tenantId());

        // Verify new name persisted
        ImConversation updated = conversationService.getById(groupId, tenantId());
        assertThat(updated.getName()).isEqualTo(newName);

        // Verify system message about rename
        List<ImMessage> messages = messageService.getMessagesAfterSeq(groupId, 0L, 100, tenantId());
        assertThat(messages).isNotEmpty();
        ImMessage lastMsg = messages.get(messages.size() - 1);
        assertThat(lastMsg.getContent()).contains("renamed the group");
        assertThat(lastMsg.getContent()).contains(newName);
    }

    @Test
    @Order(21)
    void updateConversation_nonMemberCannotRename() {
        ImConversation group = createGroup(
                "Rename Block " + ts, userId(), List.of(user2.getId()));

        ConversationUpdateRequest updateReq = new ConversationUpdateRequest();
        updateReq.setName("Hacked Name");

        assertThatThrownBy(() ->
                conversationService.updateConversation(group.getId(), updateReq, outsiderUser.getId(), tenantId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a member");
    }

    // ========== Hide Conversation Tests ==========

    @Test
    @Order(30)
    void hideConversation_hiddenNotInList() {
        ImConversation group = createGroup(
                "Hide Test " + ts, userId(), List.of(user2.getId()));
        Long groupId = group.getId();

        // Send a message so the conversation appears in the list
        sendTextMessage(groupId, userId(), "Visible message");

        // Verify it is in the list
        List<ConversationListItem> beforeList = conversationService.listByUser(userId(), tenantId());
        assertThat(beforeList).anyMatch(item -> item.getConversationId().equals(groupId));

        // Hide the conversation
        conversationService.hideConversation(groupId, userId(), tenantId());

        // Verify it is NOT in the list
        List<ConversationListItem> afterList = conversationService.listByUser(userId(), tenantId());
        assertThat(afterList).noneMatch(item -> item.getConversationId().equals(groupId));
    }

    @Test
    @Order(31)
    void hideConversation_unhidesOnNewMessage() {
        ImConversation group = createGroup(
                "Unhide Test " + ts, userId(), List.of(user2.getId()));
        Long groupId = group.getId();

        sendTextMessage(groupId, userId(), "Initial message");

        // Hide it
        conversationService.hideConversation(groupId, userId(), tenantId());

        // Verify hidden
        List<ConversationListItem> hiddenList = conversationService.listByUser(userId(), tenantId());
        assertThat(hiddenList).noneMatch(item -> item.getConversationId().equals(groupId));

        // Send a new message (from user2) which should unhide for all members
        sendTextMessage(groupId, user2.getId(), "New message unhides");

        // Verify conversation reappears
        List<ConversationListItem> visibleList = conversationService.listByUser(userId(), tenantId());
        assertThat(visibleList).anyMatch(item -> item.getConversationId().equals(groupId));
    }

    // ========== Message Recall Tests ==========

    @Test
    @Order(40)
    void recallMessage_withinTimeLimit() {
        ImConversation group = createGroup(
                "Recall Test " + ts, userId(), List.of(user2.getId()));

        ImMessage msg = sendTextMessage(group.getId(), userId(), "Message to recall " + ts);
        assertThat(msg.getRecalled()).isFalse();

        ImMessage recalled = messageService.recallMessage(msg.getId(), userId(), tenantId());

        assertThat(recalled.getRecalled()).isTrue();
        assertThat(recalled.getContent()).isNull();
        assertThat(recalled.getCardPayload()).isNull();
        assertThat(recalled.getAttachments()).isNull();
    }

    @Test
    @Order(41)
    void recallMessage_wrongSenderThrowsException() {
        ImConversation group = createGroup(
                "Recall Wrong Sender " + ts, userId(), List.of(user2.getId()));

        ImMessage msg = sendTextMessage(group.getId(), userId(), "My message " + ts);

        // user2 tries to recall userId's message — should fail
        assertThatThrownBy(() ->
                messageService.recallMessage(msg.getId(), user2.getId(), tenantId()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ========== Message Search Tests ==========

    @Test
    @Order(50)
    void searchMessages_findsMatchingContent() {
        ImConversation group = createGroup(
                "Search Test " + ts, userId(), List.of(user2.getId()));
        Long groupId = group.getId();

        String uniqueKeyword = "xyzSearch" + ts;
        sendTextMessage(groupId, userId(), "Normal message");
        sendTextMessage(groupId, userId(), "Contains " + uniqueKeyword + " here");
        sendTextMessage(groupId, userId(), "Another normal message");

        List<MessageSearchResult> results = messageService.searchMessages(
                uniqueKeyword, groupId, userId(), tenantId(), 50);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getContent()).contains(uniqueKeyword);
        assertThat(results.get(0).getConversationId()).isEqualTo(groupId);
    }

    @Test
    @Order(51)
    void searchMessages_respectsMembership() {
        ImConversation group1 = createGroup(
                "Search Scope 1 " + ts, userId(), List.of(user2.getId()));
        ImConversation group2 = createGroup(
                "Search Scope 2 " + ts, userId(), List.of(user3.getId()));

        String uniqueKeyword = "memberCheck" + ts;
        sendTextMessage(group1.getId(), userId(), uniqueKeyword + " in group1");
        sendTextMessage(group2.getId(), userId(), uniqueKeyword + " in group2");

        // user2 searches globally — should only find group1's message
        List<MessageSearchResult> user2Results = messageService.searchMessages(
                uniqueKeyword, null, user2.getId(), tenantId(), 50);
        assertThat(user2Results).hasSize(1);
        assertThat(user2Results.get(0).getConversationId()).isEqualTo(group1.getId());

        // user3 searches globally — should only find group2's message
        List<MessageSearchResult> user3Results = messageService.searchMessages(
                uniqueKeyword, null, user3.getId(), tenantId(), 50);
        assertThat(user3Results).hasSize(1);
        assertThat(user3Results.get(0).getConversationId()).isEqualTo(group2.getId());

        // owner (userId) searches globally — should find both
        List<MessageSearchResult> ownerResults = messageService.searchMessages(
                uniqueKeyword, null, userId(), tenantId(), 50);
        assertThat(ownerResults).hasSize(2);
    }

    @Test
    @Order(52)
    void searchMessages_excludesRecalledMessages() {
        ImConversation group = createGroup(
                "Search Recall " + ts, userId(), List.of(user2.getId()));

        String uniqueKeyword = "recallSearch" + ts;
        ImMessage msg = sendTextMessage(group.getId(), userId(), uniqueKeyword + " to recall");
        sendTextMessage(group.getId(), userId(), uniqueKeyword + " to keep");

        // Recall the first message
        messageService.recallMessage(msg.getId(), userId(), tenantId());

        // Search should only find the non-recalled message
        List<MessageSearchResult> results = messageService.searchMessages(
                uniqueKeyword, group.getId(), userId(), tenantId(), 50);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getContent()).contains("to keep");
    }

    // ========== Message Forward Tests ==========

    @Test
    @Order(60)
    void forwardMessage_createsNewMessage() {
        ImConversation group1 = createGroup(
                "Forward Source " + ts, userId(), List.of(user2.getId()));
        ImConversation group2 = createGroup(
                "Forward Target " + ts, userId(), List.of(user2.getId()));

        String content = "Forward me " + ts;
        ImMessage original = sendTextMessage(group1.getId(), userId(), content);

        ImMessage forwarded = messageService.forwardMessage(
                original.getId(), group2.getId(), userId(), tenantId());

        assertThat(forwarded).isNotNull();
        assertThat(forwarded.getId()).isNotEqualTo(original.getId());
        assertThat(forwarded.getConversationId()).isEqualTo(group2.getId());
        assertThat(forwarded.getContent()).isEqualTo(content);
        assertThat(forwarded.getForwardedFromId()).isEqualTo(original.getId());
        assertThat(forwarded.getSenderId()).isEqualTo(userId());

        // Verify the forwarded message appears in group2's history
        List<ImMessage> group2Messages = messageService.getMessagesAfterSeq(
                group2.getId(), 0L, 100, tenantId());
        assertThat(group2Messages).anyMatch(m ->
                m.getForwardedFromId() != null && m.getForwardedFromId().equals(original.getId()));
    }

    @Test
    @Order(61)
    void forwardMessage_nonexistentMessageThrows() {
        ImConversation group = createGroup(
                "Forward Fail " + ts, userId(), List.of(user2.getId()));

        assertThatThrownBy(() ->
                messageService.forwardMessage(-999L, group.getId(), userId(), tenantId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }
}
