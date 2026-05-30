package com.auraboot.framework.im.message;

import com.auraboot.framework.im.model.ImConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ImSystemMessageBuilderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void memberJoinedHasParams() {
        JsonNode root = parse(ImSystemMessageBuilder.memberJoined(101L, "Alice", 11L, "Bob"));
        assertThat(root.get("subType").asText()).isEqualTo(ImConstants.SYS_MEMBER_JOINED);
        assertThat(root.get("params").get("memberId").asLong()).isEqualTo(101L);
        assertThat(root.get("params").get("memberName").asText()).isEqualTo("Alice");
        assertThat(root.get("params").get("byUserId").asLong()).isEqualTo(11L);
        assertThat(root.get("params").get("byUserName").asText()).isEqualTo("Bob");
    }

    @Test
    void memberJoinedBatchHasListParams() {
        JsonNode root = parse(ImSystemMessageBuilder.memberJoinedBatch(
            List.of(101L, 102L), List.of("Alice", "Bob"), 11L, "Owner"));
        assertThat(root.get("subType").asText()).isEqualTo(ImConstants.SYS_MEMBER_JOINED);
        JsonNode ids = root.get("params").get("memberIds");
        assertThat(ids.isArray()).isTrue();
        assertThat(ids.size()).isEqualTo(2);
        assertThat(ids.get(0).asLong()).isEqualTo(101L);
        assertThat(ids.get(1).asLong()).isEqualTo(102L);
        JsonNode names = root.get("params").get("memberNames");
        assertThat(names.isArray()).isTrue();
        assertThat(names.size()).isEqualTo(2);
        assertThat(names.get(0).asText()).isEqualTo("Alice");
        assertThat(root.get("params").get("byUserId").asLong()).isEqualTo(11L);
        assertThat(root.get("params").get("byUserName").asText()).isEqualTo("Owner");
    }

    @Test
    void memberLeftHasParams() {
        JsonNode root = parse(ImSystemMessageBuilder.memberLeft(22L, "Charlie"));
        assertThat(root.get("subType").asText()).isEqualTo(ImConstants.SYS_MEMBER_LEFT);
        assertThat(root.get("params").get("memberId").asLong()).isEqualTo(22L);
        assertThat(root.get("params").get("memberName").asText()).isEqualTo("Charlie");
    }

    @Test
    void memberRemovedHasParams() {
        JsonNode root = parse(ImSystemMessageBuilder.memberRemoved(22L, "Charlie", 11L, "Owner"));
        assertThat(root.get("subType").asText()).isEqualTo(ImConstants.SYS_MEMBER_REMOVED);
        assertThat(root.get("params").get("memberId").asLong()).isEqualTo(22L);
        assertThat(root.get("params").get("memberName").asText()).isEqualTo("Charlie");
        assertThat(root.get("params").get("byUserId").asLong()).isEqualTo(11L);
        assertThat(root.get("params").get("byUserName").asText()).isEqualTo("Owner");
    }

    @Test
    void conversationCreatedHasParams() {
        JsonNode root = parse(ImSystemMessageBuilder.conversationCreated(11L, "Alice"));
        assertThat(root.get("subType").asText()).isEqualTo(ImConstants.SYS_CONVERSATION_CREATED);
        assertThat(root.get("params").get("byUserId").asLong()).isEqualTo(11L);
        assertThat(root.get("params").get("byUserName").asText()).isEqualTo("Alice");
    }

    @Test
    void conversationRenamedHasParams() {
        JsonNode root = parse(ImSystemMessageBuilder.conversationRenamed("Old", "New", 11L, "Alice"));
        assertThat(root.get("subType").asText()).isEqualTo(ImConstants.SYS_CONVERSATION_RENAMED);
        assertThat(root.get("params").get("oldName").asText()).isEqualTo("Old");
        assertThat(root.get("params").get("newName").asText()).isEqualTo("New");
        assertThat(root.get("params").get("byUserId").asLong()).isEqualTo(11L);
        assertThat(root.get("params").get("byUserName").asText()).isEqualTo("Alice");
    }

    @Test
    void announcementUpdatedHasParams() {
        JsonNode root = parse(ImSystemMessageBuilder.announcementUpdated(11L, "Alice"));
        assertThat(root.get("subType").asText()).isEqualTo(ImConstants.SYS_ANNOUNCEMENT_UPDATED);
        assertThat(root.get("params").get("byUserId").asLong()).isEqualTo(11L);
        assertThat(root.get("params").get("byUserName").asText()).isEqualTo("Alice");
    }

    @Test
    void announcementClearedHasParams() {
        JsonNode root = parse(ImSystemMessageBuilder.announcementCleared(11L, "Alice"));
        assertThat(root.get("subType").asText()).isEqualTo(ImConstants.SYS_ANNOUNCEMENT_CLEARED);
        assertThat(root.get("params").get("byUserId").asLong()).isEqualTo(11L);
    }

    @Test
    void agentSettingsChangedHasParams() {
        JsonNode root = parse(ImSystemMessageBuilder.agentSettingsChanged(11L, "Alice"));
        assertThat(root.get("subType").asText()).isEqualTo(ImConstants.SYS_AGENT_SETTINGS_CHANGED);
        assertThat(root.get("params").get("byUserId").asLong()).isEqualTo(11L);
    }

    @Test
    void conversationDissolvedHasParams() {
        JsonNode root = parse(ImSystemMessageBuilder.conversationDissolved(11L, "Alice"));
        assertThat(root.get("subType").asText()).isEqualTo(ImConstants.SYS_CONVERSATION_DISSOLVED);
        assertThat(root.get("params").get("byUserId").asLong()).isEqualTo(11L);
        assertThat(root.get("params").get("byUserName").asText()).isEqualTo("Alice");
    }

    @Test
    void reservedArchivedReturnsValidJson() {
        JsonNode root = parse(ImSystemMessageBuilder.conversationArchived(11L, "Alice"));
        assertThat(root.get("subType").asText()).isEqualTo(ImConstants.SYS_CONVERSATION_ARCHIVED);
        assertThat(root.get("params").get("byUserId").asLong()).isEqualTo(11L);
    }

    @Test
    void reservedPinnedMsgReturnsValidJson() {
        JsonNode root = parse(ImSystemMessageBuilder.conversationPinnedMsg(11L, "Alice", 42L));
        assertThat(root.get("subType").asText()).isEqualTo(ImConstants.SYS_CONVERSATION_PINNED_MSG);
        assertThat(root.get("params").get("messageId").asLong()).isEqualTo(42L);
    }
}
