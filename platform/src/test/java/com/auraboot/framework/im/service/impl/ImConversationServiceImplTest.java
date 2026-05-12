package com.auraboot.framework.im.service.impl;

import com.auraboot.framework.agent.entity.AgentDefinition;
import com.auraboot.framework.agent.mapper.AgentDefinitionMapper;
import com.auraboot.framework.im.dto.ConversationAgentSettingsRequest;
import com.auraboot.framework.im.dto.ConversationCreateRequest;
import com.auraboot.framework.im.dto.ConversationListItem;
import com.auraboot.framework.im.dto.ConversationMemberInfo;
import com.auraboot.framework.im.dto.ConversationUpdateRequest;
import com.auraboot.framework.im.dto.UnreadSummary;
import com.auraboot.framework.im.mapper.ImConversationMapper;
import com.auraboot.framework.im.mapper.ImConversationMemberMapper;
import com.auraboot.framework.im.mapper.ImMessageMapper;
import com.auraboot.framework.im.model.ImConstants;
import com.auraboot.framework.im.model.ImConversation;
import com.auraboot.framework.im.model.ImConversationMember;
import com.auraboot.framework.im.model.ImMessage;
import com.auraboot.framework.im.service.ImMessageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImConversationServiceImplTest {

    @Mock private ImConversationMapper conversationMapper;
    @Mock private ImConversationMemberMapper memberMapper;
    @Mock private ImMessageMapper messageMapper;
    @Mock private ImMessageService imMessageService;
    @Mock private AgentDefinitionMapper agentDefinitionMapper;

    private ImConversationServiceImpl service;

    private static final Long TENANT_ID = 1L;
    private static final Long USER_ID = 10L;
    private static final Long OTHER_ID = 11L;
    private static final Long CONV_ID = 100L;

    @BeforeEach
    void setUp() {
        service = new ImConversationServiceImpl(conversationMapper, memberMapper, messageMapper,
                imMessageService, agentDefinitionMapper);
    }

    private ConversationCreateRequest req(String type) {
        ConversationCreateRequest r = new ConversationCreateRequest();
        r.setType(type);
        return r;
    }

    private ImConversation conv(Long id, String type, Long ownerId) {
        ImConversation c = new ImConversation();
        c.setId(id);
        c.setTenantId(TENANT_ID);
        c.setType(type);
        c.setOwnerId(ownerId);
        c.setMaxSeq(0L);
        return c;
    }

    // =================== create ===================

    @Test
    void create_privateConversationReturnsExistingIfAny() {
        ConversationCreateRequest r = req(ImConstants.TYPE_PRIVATE);
        r.setMemberIds(List.of(OTHER_ID));

        ImConversation existing = conv(CONV_ID, ImConstants.TYPE_PRIVATE, USER_ID);
        when(memberMapper.findConversationIdsByMember(TENANT_ID, ImConstants.MEMBER_TYPE_HUMAN, USER_ID))
                .thenReturn(List.of(CONV_ID));
        when(memberMapper.findConversationIdsByMember(TENANT_ID, ImConstants.MEMBER_TYPE_HUMAN, OTHER_ID))
                .thenReturn(List.of(CONV_ID));
        when(conversationMapper.selectById(CONV_ID)).thenReturn(existing);

        ImConversation result = service.create(r, USER_ID, TENANT_ID);

        assertThat(result).isSameAs(existing);
        verify(conversationMapper, never()).insert(any(ImConversation.class));
    }

    @Test
    void create_objectMissingBoundFields_throws() {
        ConversationCreateRequest r = req(ImConstants.TYPE_OBJECT);
        assertThatThrownBy(() -> service.create(r, USER_ID, TENANT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("boundModelCode");
    }

    @Test
    void create_objectReturnsExistingByBoundRecord() {
        ConversationCreateRequest r = req(ImConstants.TYPE_OBJECT);
        r.setBoundModelCode("crm_lead");
        r.setBoundRecordId(5L);

        ImConversation existing = conv(CONV_ID, ImConstants.TYPE_OBJECT, USER_ID);
        when(conversationMapper.selectOne(any())).thenReturn(existing);

        ImConversation result = service.create(r, USER_ID, TENANT_ID);
        assertThat(result).isSameAs(existing);
        verify(conversationMapper, never()).insert(any(ImConversation.class));
    }

    @Test
    void create_groupCreatesConversationAndAddsOwner() {
        ConversationCreateRequest r = req(ImConstants.TYPE_GROUP);
        r.setName("My Group");
        r.setMemberIds(List.of(OTHER_ID));

        service.create(r, USER_ID, TENANT_ID);

        ArgumentCaptor<ImConversation> convCaptor = ArgumentCaptor.forClass(ImConversation.class);
        verify(conversationMapper).insert(convCaptor.capture());
        assertThat(convCaptor.getValue().getOwnerId()).isEqualTo(USER_ID);
        assertThat(convCaptor.getValue().getName()).isEqualTo("My Group");
        assertThat(convCaptor.getValue().getType()).isEqualTo(ImConstants.TYPE_GROUP);

        // owner + 1 other = 2 inserts on member mapper
        verify(memberMapper, times(2)).insert(any(ImConversationMember.class));
    }

    @Test
    void create_objectAutoNamesIfNull() {
        ConversationCreateRequest r = req(ImConstants.TYPE_OBJECT);
        r.setBoundModelCode("crm_lead");
        r.setBoundRecordId(42L);
        when(conversationMapper.selectOne(any())).thenReturn(null);

        service.create(r, USER_ID, TENANT_ID);

        ArgumentCaptor<ImConversation> captor = ArgumentCaptor.forClass(ImConversation.class);
        verify(conversationMapper).insert(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("crm_lead #42");
    }

    @Test
    void create_skipsSelfWhenAddingMembers() {
        ConversationCreateRequest r = req(ImConstants.TYPE_GROUP);
        r.setName("g");
        r.setMemberIds(List.of(USER_ID, OTHER_ID));

        service.create(r, USER_ID, TENANT_ID);

        // Only owner + OTHER_ID; USER_ID is skipped because it equals creator
        verify(memberMapper, times(2)).insert(any(ImConversationMember.class));
    }

    @Test
    void create_withAgentMembersSendsWelcome() {
        ConversationCreateRequest r = req(ImConstants.TYPE_GROUP);
        r.setName("g");
        r.setMemberIds(List.of());
        r.setAgentIds(List.of(50L));

        AgentDefinition agent = new AgentDefinition();
        agent.setName("Bot");
        agent.setEmployeeId(99L);
        when(agentDefinitionMapper.selectById(50L)).thenReturn(agent);

        service.create(r, USER_ID, TENANT_ID);

        verify(imMessageService).sendSystemMessage(nullable(Long.class), eq(TENANT_ID),
                eq("system"), anyString(), isNull(), anyString());
    }

    // =================== listByUser ===================

    @Test
    void listByUser_noMembership_returnsEmpty() {
        when(memberMapper.findVisibleConversationIdsByMember(TENANT_ID,
                ImConstants.MEMBER_TYPE_HUMAN, USER_ID)).thenReturn(List.of());
        assertThat(service.listByUser(USER_ID, TENANT_ID)).isEmpty();
    }

    @Test
    void listByUser_buildsItemsAndSortsByLastMessageDesc() {
        when(memberMapper.findVisibleConversationIdsByMember(TENANT_ID,
                ImConstants.MEMBER_TYPE_HUMAN, USER_ID)).thenReturn(List.of(1L, 2L));

        ImConversation c1 = conv(1L, ImConstants.TYPE_GROUP, USER_ID);
        c1.setMaxSeq(5L);
        ImConversation c2 = conv(2L, ImConstants.TYPE_PRIVATE, USER_ID);
        c2.setMaxSeq(2L);
        when(conversationMapper.selectById(1L)).thenReturn(c1);
        when(conversationMapper.selectById(2L)).thenReturn(c2);

        ImConversationMember mem1 = new ImConversationMember();
        mem1.setLastReadSeq(3L);
        mem1.setMuted(false);
        mem1.setPinned(true);
        when(memberMapper.findMember(1L, ImConstants.MEMBER_TYPE_HUMAN, USER_ID, TENANT_ID))
                .thenReturn(mem1);
        when(memberMapper.findMember(2L, ImConstants.MEMBER_TYPE_HUMAN, USER_ID, TENANT_ID))
                .thenReturn(null);

        ImMessage m1 = new ImMessage();
        m1.setContent("a");
        m1.setMessageType("text");
        m1.setCreatedAt(Instant.now().minusSeconds(10));
        ImMessage m2 = new ImMessage();
        m2.setContent("b");
        m2.setMessageType("text");
        m2.setCreatedAt(Instant.now());
        when(messageMapper.findBeforeSeq(eq(1L), eq(TENANT_ID), eq(Long.MAX_VALUE), eq(1)))
                .thenReturn(List.of(m1));
        when(messageMapper.findBeforeSeq(eq(2L), eq(TENANT_ID), eq(Long.MAX_VALUE), eq(1)))
                .thenReturn(List.of(m2));
        when(memberMapper.findHumanMemberIds(anyLong(), eq(TENANT_ID))).thenReturn(List.of(USER_ID));

        List<ConversationListItem> items = service.listByUser(USER_ID, TENANT_ID);

        assertThat(items).hasSize(2);
        // Newer m2 (conv 2) should come first
        assertThat(items.get(0).getConversationId()).isEqualTo(2L);
        assertThat(items.get(1).getConversationId()).isEqualTo(1L);
        assertThat(items.get(1).getUnreadCount()).isEqualTo(2L);
        assertThat(items.get(1).getPinned()).isTrue();
    }

    @Test
    void listByUser_skipsNullConv() {
        when(memberMapper.findVisibleConversationIdsByMember(TENANT_ID,
                ImConstants.MEMBER_TYPE_HUMAN, USER_ID)).thenReturn(List.of(1L));
        when(conversationMapper.selectById(1L)).thenReturn(null);
        assertThat(service.listByUser(USER_ID, TENANT_ID)).isEmpty();
    }

    @Test
    void listByUser_filtersByType() {
        when(memberMapper.findVisibleConversationIdsByMember(TENANT_ID,
                ImConstants.MEMBER_TYPE_HUMAN, USER_ID)).thenReturn(List.of(1L));
        ImConversation c1 = conv(1L, ImConstants.TYPE_GROUP, USER_ID);
        when(conversationMapper.selectById(1L)).thenReturn(c1);
        when(memberMapper.findMember(anyLong(), anyString(), anyLong(), anyLong())).thenReturn(null);
        when(messageMapper.findBeforeSeq(anyLong(), anyLong(), anyLong(), anyInt())).thenReturn(List.of());
        when(memberMapper.findHumanMemberIds(anyLong(), anyLong())).thenReturn(List.of());

        assertThat(service.listByUser(USER_ID, TENANT_ID, ImConstants.TYPE_PRIVATE)).isEmpty();
        assertThat(service.listByUser(USER_ID, TENANT_ID, ImConstants.TYPE_GROUP)).hasSize(1);
        assertThat(service.listByUser(USER_ID, TENANT_ID, null)).hasSize(1);
    }

    // =================== getByIdAsListItem ===================

    @Test
    void getByIdAsListItem_returnsNullWhenConvMissing() {
        when(conversationMapper.selectOne(any())).thenReturn(null);
        assertThat(service.getByIdAsListItem(CONV_ID, USER_ID, TENANT_ID)).isNull();
    }

    @Test
    void getByIdAsListItem_buildsResponse() {
        ImConversation c = conv(CONV_ID, ImConstants.TYPE_GROUP, USER_ID);
        c.setMaxSeq(5L);
        c.setConductorAgentId(88L);
        c.setAiContextWindow(24);
        when(conversationMapper.selectOne(any())).thenReturn(c);
        ImConversationMember mem = new ImConversationMember();
        mem.setLastReadSeq(2L);
        mem.setMuted(true);
        mem.setPinned(false);
        when(memberMapper.findMember(CONV_ID, ImConstants.MEMBER_TYPE_HUMAN, USER_ID, TENANT_ID))
                .thenReturn(mem);
        when(messageMapper.findBeforeSeq(eq(CONV_ID), anyLong(), anyLong(), anyInt())).thenReturn(List.of());
        when(memberMapper.findHumanMemberIds(CONV_ID, TENANT_ID)).thenReturn(List.of(USER_ID, OTHER_ID));

        ConversationListItem item = service.getByIdAsListItem(CONV_ID, USER_ID, TENANT_ID);
        assertThat(item.getUnreadCount()).isEqualTo(3L);
        assertThat(item.getMuted()).isTrue();
        assertThat(item.getMemberCount()).isEqualTo(2);
        assertThat(item.getConductorAgentId()).isEqualTo(88L);
        assertThat(item.getAiContextWindow()).isEqualTo(24);
        assertThat(item.getAiEnabled()).isTrue();
    }

    // =================== addMembers / addAgentMembers ===================

    @Test
    void addMembers_skipsExistingMembers() {
        when(memberMapper.findMember(CONV_ID, ImConstants.MEMBER_TYPE_HUMAN, OTHER_ID, TENANT_ID))
                .thenReturn(new ImConversationMember()); // already exists
        when(memberMapper.findMember(CONV_ID, ImConstants.MEMBER_TYPE_HUMAN, 12L, TENANT_ID))
                .thenReturn(null);

        service.addMembers(CONV_ID, List.of(OTHER_ID, 12L), TENANT_ID);

        // Only the new one is inserted
        verify(memberMapper, times(1)).insert(any(ImConversationMember.class));
    }

    @Test
    void addAgentMembers_skipsExistingAndSendsWelcomeForNew() {
        when(memberMapper.findMember(CONV_ID, ImConstants.MEMBER_TYPE_AGENT, 9L, TENANT_ID))
                .thenReturn(null);
        AgentDefinition agent = new AgentDefinition();
        agent.setName("Aurabot");
        when(agentDefinitionMapper.selectById(9L)).thenReturn(agent);

        service.addAgentMembers(CONV_ID, List.of(9L), TENANT_ID);
        verify(memberMapper).insert(any(ImConversationMember.class));
        verify(imMessageService).sendSystemMessage(eq(CONV_ID), eq(TENANT_ID),
                eq("system"), anyString(), any(), anyString());
    }

    @Test
    void addAgentMembers_existingDoesNotSendWelcome() {
        when(memberMapper.findMember(CONV_ID, ImConstants.MEMBER_TYPE_AGENT, 9L, TENANT_ID))
                .thenReturn(new ImConversationMember());

        service.addAgentMembers(CONV_ID, List.of(9L), TENANT_ID);
        verify(memberMapper, never()).insert(any(ImConversationMember.class));
        verify(imMessageService, never()).sendSystemMessage(anyLong(), anyLong(), anyString(),
                anyString(), any(), any());
    }

    // =================== removeMember / isMember ===================

    @Test
    void removeMember_delegatesToMapper() {
        service.removeMember(CONV_ID, ImConstants.MEMBER_TYPE_HUMAN, USER_ID, TENANT_ID);
        verify(memberMapper).delete(any());
    }

    @Test
    void isMember_returnsTrueWhenFound() {
        when(memberMapper.findMember(CONV_ID, ImConstants.MEMBER_TYPE_HUMAN, USER_ID, TENANT_ID))
                .thenReturn(new ImConversationMember());
        assertThat(service.isMember(CONV_ID, USER_ID, TENANT_ID)).isTrue();
    }

    @Test
    void isMember_returnsFalseWhenNotFound() {
        when(memberMapper.findMember(CONV_ID, ImConstants.MEMBER_TYPE_HUMAN, USER_ID, TENANT_ID))
                .thenReturn(null);
        assertThat(service.isMember(CONV_ID, USER_ID, TENANT_ID)).isFalse();
    }

    // =================== getUnreadSummary ===================

    @Test
    void getUnreadSummary_aggregatesAcrossConvs() {
        when(memberMapper.findConversationIdsByMember(TENANT_ID, ImConstants.MEMBER_TYPE_HUMAN, USER_ID))
                .thenReturn(List.of(1L, 2L, 3L));
        ImConversation c1 = conv(1L, ImConstants.TYPE_GROUP, USER_ID);
        c1.setMaxSeq(5L);
        ImConversation c2 = conv(2L, ImConstants.TYPE_GROUP, USER_ID);
        c2.setMaxSeq(10L);
        when(conversationMapper.selectById(1L)).thenReturn(c1);
        when(conversationMapper.selectById(2L)).thenReturn(c2);
        when(conversationMapper.selectById(3L)).thenReturn(null); // skipped

        ImConversationMember m1 = new ImConversationMember();
        m1.setLastReadSeq(3L);
        ImConversationMember m2 = new ImConversationMember();
        m2.setLastReadSeq(10L); // 0 unread
        when(memberMapper.findMember(1L, ImConstants.MEMBER_TYPE_HUMAN, USER_ID, TENANT_ID))
                .thenReturn(m1);
        when(memberMapper.findMember(2L, ImConstants.MEMBER_TYPE_HUMAN, USER_ID, TENANT_ID))
                .thenReturn(m2);

        UnreadSummary s = service.getUnreadSummary(USER_ID, TENANT_ID);
        assertThat(s.getTotalUnread()).isEqualTo(2L);
        assertThat(s.getConversations()).hasSize(1);
        assertThat(s.getConversations().get(0).getConversationId()).isEqualTo(1L);
    }

    // =================== findOrCreateBotConversation ===================

    @Test
    void findOrCreateBotConversation_returnsExisting() {
        when(memberMapper.findConversationIdsByMember(TENANT_ID, ImConstants.MEMBER_TYPE_HUMAN, USER_ID))
                .thenReturn(List.of(50L));
        ImConversation bot = conv(50L, ImConstants.TYPE_BOT, USER_ID);
        when(conversationMapper.selectById(50L)).thenReturn(bot);

        ImConversation result = service.findOrCreateBotConversation(USER_ID, TENANT_ID);
        assertThat(result).isSameAs(bot);
        verify(conversationMapper, never()).insert(any(ImConversation.class));
    }

    @Test
    void findOrCreateBotConversation_createsWhenAbsent() {
        when(memberMapper.findConversationIdsByMember(TENANT_ID, ImConstants.MEMBER_TYPE_HUMAN, USER_ID))
                .thenReturn(List.of());

        ImConversation result = service.findOrCreateBotConversation(USER_ID, TENANT_ID);

        assertThat(result.getType()).isEqualTo(ImConstants.TYPE_BOT);
        assertThat(result.getOwnerId()).isEqualTo(USER_ID);
        verify(conversationMapper).insert(any(ImConversation.class));
        verify(memberMapper).insert(any(ImConversationMember.class));
    }

    // =================== updateMemberSettings ===================

    @Test
    void updateMemberSettings_throwsWhenNotMember() {
        when(memberMapper.findMember(CONV_ID, ImConstants.MEMBER_TYPE_HUMAN, USER_ID, TENANT_ID))
                .thenReturn(null);
        assertThatThrownBy(() -> service.updateMemberSettings(CONV_ID, USER_ID, TENANT_ID, true, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateMemberSettings_appliesFlags() {
        ImConversationMember mem = new ImConversationMember();
        when(memberMapper.findMember(CONV_ID, ImConstants.MEMBER_TYPE_HUMAN, USER_ID, TENANT_ID))
                .thenReturn(mem);

        service.updateMemberSettings(CONV_ID, USER_ID, TENANT_ID, true, false);

        ArgumentCaptor<ImConversationMember> captor = ArgumentCaptor.forClass(ImConversationMember.class);
        verify(memberMapper).update(captor.capture(), any());
        assertThat(captor.getValue().getMuted()).isTrue();
        assertThat(captor.getValue().getPinned()).isFalse();
    }

    // =================== updateAgentSettings ===================

    @Test
    void updateAgentSettings_setsConductorAndContextWindow() {
        when(memberMapper.findMember(CONV_ID, ImConstants.MEMBER_TYPE_HUMAN, USER_ID, TENANT_ID))
                .thenReturn(new ImConversationMember());
        when(memberMapper.findMember(CONV_ID, ImConstants.MEMBER_TYPE_AGENT, 9L, TENANT_ID))
                .thenReturn(new ImConversationMember());
        ImConversation c = conv(CONV_ID, ImConstants.TYPE_GROUP, USER_ID);
        when(conversationMapper.selectOne(any())).thenReturn(c);

        ConversationAgentSettingsRequest request = new ConversationAgentSettingsRequest();
        request.setConductorAgentId(9L);
        request.setAiContextWindow(24);
        request.setAiEnabled(true);

        service.updateAgentSettings(CONV_ID, request, USER_ID, TENANT_ID);

        ArgumentCaptor<ImConversation> captor = ArgumentCaptor.forClass(ImConversation.class);
        verify(conversationMapper).updateById(captor.capture());
        assertThat(captor.getValue().getConductorAgentId()).isEqualTo(9L);
        assertThat(captor.getValue().getAiContextWindow()).isEqualTo(24);
        assertThat(captor.getValue().getUpdatedAt()).isNotNull();
    }

    @Test
    void updateAgentSettings_disablesAiByClearingConductor() {
        when(memberMapper.findMember(CONV_ID, ImConstants.MEMBER_TYPE_HUMAN, USER_ID, TENANT_ID))
                .thenReturn(new ImConversationMember());
        ImConversation c = conv(CONV_ID, ImConstants.TYPE_GROUP, USER_ID);
        c.setConductorAgentId(9L);
        when(conversationMapper.selectOne(any())).thenReturn(c);

        ConversationAgentSettingsRequest request = new ConversationAgentSettingsRequest();
        request.setAiEnabled(false);

        service.updateAgentSettings(CONV_ID, request, USER_ID, TENANT_ID);

        ArgumentCaptor<ImConversation> captor = ArgumentCaptor.forClass(ImConversation.class);
        verify(conversationMapper).updateById(captor.capture());
        assertThat(captor.getValue().getConductorAgentId()).isNull();
    }

    @Test
    void updateAgentSettings_rejectsNonGroup() {
        when(memberMapper.findMember(CONV_ID, ImConstants.MEMBER_TYPE_HUMAN, USER_ID, TENANT_ID))
                .thenReturn(new ImConversationMember());
        when(conversationMapper.selectOne(any())).thenReturn(conv(CONV_ID, ImConstants.TYPE_PRIVATE, USER_ID));

        assertThatThrownBy(() -> service.updateAgentSettings(CONV_ID,
                new ConversationAgentSettingsRequest(), USER_ID, TENANT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("group");
        verify(conversationMapper, never()).updateById(any(ImConversation.class));
    }

    @Test
    void updateAgentSettings_rejectsMissingAgentMember() {
        when(memberMapper.findMember(CONV_ID, ImConstants.MEMBER_TYPE_HUMAN, USER_ID, TENANT_ID))
                .thenReturn(new ImConversationMember());
        when(memberMapper.findMember(CONV_ID, ImConstants.MEMBER_TYPE_AGENT, 9L, TENANT_ID))
                .thenReturn(null);
        when(conversationMapper.selectOne(any())).thenReturn(conv(CONV_ID, ImConstants.TYPE_GROUP, USER_ID));

        ConversationAgentSettingsRequest request = new ConversationAgentSettingsRequest();
        request.setConductorAgentId(9L);

        assertThatThrownBy(() -> service.updateAgentSettings(CONV_ID, request, USER_ID, TENANT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Conductor agent");
        verify(conversationMapper, never()).updateById(any(ImConversation.class));
    }

    // =================== dissolveGroup ===================

    @Test
    void dissolveGroup_throwsWhenNotFound() {
        when(conversationMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.dissolveGroup(CONV_ID, USER_ID, TENANT_ID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dissolveGroup_throwsWhenNotGroup() {
        when(conversationMapper.selectOne(any())).thenReturn(conv(CONV_ID, ImConstants.TYPE_PRIVATE, USER_ID));
        assertThatThrownBy(() -> service.dissolveGroup(CONV_ID, USER_ID, TENANT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("group");
    }

    @Test
    void dissolveGroup_throwsWhenNotOwner() {
        when(conversationMapper.selectOne(any())).thenReturn(conv(CONV_ID, ImConstants.TYPE_GROUP, OTHER_ID));
        assertThatThrownBy(() -> service.dissolveGroup(CONV_ID, USER_ID, TENANT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("owner");
    }

    @Test
    void dissolveGroup_deletesAndReturnsHumanMembers() {
        when(conversationMapper.selectOne(any())).thenReturn(conv(CONV_ID, ImConstants.TYPE_GROUP, USER_ID));
        List<Long> humans = List.of(USER_ID, OTHER_ID);
        when(memberMapper.findHumanMemberIds(CONV_ID, TENANT_ID)).thenReturn(humans);

        List<Long> result = service.dissolveGroup(CONV_ID, USER_ID, TENANT_ID);
        assertThat(result).isEqualTo(humans);
        verify(memberMapper).delete(any());
        verify(messageMapper).delete(any());
        verify(conversationMapper).deleteById(CONV_ID);
    }

    // =================== leaveGroup ===================

    @Test
    void leaveGroup_throwsWhenNotFound() {
        when(conversationMapper.selectOne(any())).thenReturn(null);
        assertThatThrownBy(() -> service.leaveGroup(CONV_ID, USER_ID, TENANT_ID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void leaveGroup_throwsWhenNotGroup() {
        when(conversationMapper.selectOne(any())).thenReturn(conv(CONV_ID, ImConstants.TYPE_PRIVATE, USER_ID));
        assertThatThrownBy(() -> service.leaveGroup(CONV_ID, USER_ID, TENANT_ID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void leaveGroup_throwsWhenOwner() {
        when(conversationMapper.selectOne(any())).thenReturn(conv(CONV_ID, ImConstants.TYPE_GROUP, USER_ID));
        assertThatThrownBy(() -> service.leaveGroup(CONV_ID, USER_ID, TENANT_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("owner");
    }

    @Test
    void leaveGroup_throwsWhenNotMember() {
        when(conversationMapper.selectOne(any())).thenReturn(conv(CONV_ID, ImConstants.TYPE_GROUP, OTHER_ID));
        when(memberMapper.findMember(CONV_ID, ImConstants.MEMBER_TYPE_HUMAN, USER_ID, TENANT_ID))
                .thenReturn(null);

        assertThatThrownBy(() -> service.leaveGroup(CONV_ID, USER_ID, TENANT_ID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void leaveGroup_normalLeaveSendsSystemMessageAndKeepsConversationWhenOthersRemain() {
        when(conversationMapper.selectOne(any())).thenReturn(conv(CONV_ID, ImConstants.TYPE_GROUP, OTHER_ID));
        when(memberMapper.findMember(CONV_ID, ImConstants.MEMBER_TYPE_HUMAN, USER_ID, TENANT_ID))
                .thenReturn(new ImConversationMember());
        when(memberMapper.findMembersWithInfo(CONV_ID, TENANT_ID)).thenReturn(List.of(
                ConversationMemberInfo.builder()
                        .memberType(ImConstants.MEMBER_TYPE_HUMAN)
                        .memberId(USER_ID)
                        .displayName("Alice")
                        .build()));
        when(memberMapper.findHumanMemberIds(CONV_ID, TENANT_ID))
                .thenReturn(List.of(OTHER_ID, 12L, 13L));

        service.leaveGroup(CONV_ID, USER_ID, TENANT_ID);

        verify(memberMapper).delete(any());
        verify(imMessageService).sendSystemMessage(eq(CONV_ID), eq(TENANT_ID),
                eq("system"), anyString(), any(), any());
        verify(conversationMapper, never()).deleteById(anyLong());
    }

    @Test
    void leaveGroup_autoDissolvesWhenSingleRemainingMember() {
        when(conversationMapper.selectOne(any())).thenReturn(conv(CONV_ID, ImConstants.TYPE_GROUP, OTHER_ID));
        when(memberMapper.findMember(CONV_ID, ImConstants.MEMBER_TYPE_HUMAN, USER_ID, TENANT_ID))
                .thenReturn(new ImConversationMember());
        when(memberMapper.findMembersWithInfo(CONV_ID, TENANT_ID)).thenReturn(List.of());
        when(memberMapper.findHumanMemberIds(CONV_ID, TENANT_ID))
                .thenReturn(List.of(OTHER_ID));

        service.leaveGroup(CONV_ID, USER_ID, TENANT_ID);

        verify(conversationMapper).deleteById(CONV_ID);
        verify(messageMapper, atLeastOnce()).delete(any());
    }

    // =================== updateConversation ===================

    @Test
    void updateConversation_notFound_throws() {
        when(conversationMapper.selectOne(any())).thenReturn(null);
        ConversationUpdateRequest r = new ConversationUpdateRequest();
        r.setName("New");
        assertThatThrownBy(() -> service.updateConversation(CONV_ID, r, USER_ID, TENANT_ID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateConversation_notGroup_throws() {
        when(conversationMapper.selectOne(any())).thenReturn(conv(CONV_ID, ImConstants.TYPE_PRIVATE, USER_ID));
        ConversationUpdateRequest r = new ConversationUpdateRequest();
        r.setName("X");
        assertThatThrownBy(() -> service.updateConversation(CONV_ID, r, USER_ID, TENANT_ID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateConversation_notMember_throws() {
        when(conversationMapper.selectOne(any())).thenReturn(conv(CONV_ID, ImConstants.TYPE_GROUP, OTHER_ID));
        when(memberMapper.findMember(CONV_ID, ImConstants.MEMBER_TYPE_HUMAN, USER_ID, TENANT_ID))
                .thenReturn(null);
        ConversationUpdateRequest r = new ConversationUpdateRequest();
        r.setName("X");
        assertThatThrownBy(() -> service.updateConversation(CONV_ID, r, USER_ID, TENANT_ID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updateConversation_renamesAndAnnounces() {
        when(conversationMapper.selectOne(any())).thenReturn(conv(CONV_ID, ImConstants.TYPE_GROUP, USER_ID));
        when(memberMapper.findMember(CONV_ID, ImConstants.MEMBER_TYPE_HUMAN, USER_ID, TENANT_ID))
                .thenReturn(new ImConversationMember());
        when(memberMapper.findMembersWithInfo(CONV_ID, TENANT_ID)).thenReturn(List.of(
                ConversationMemberInfo.builder()
                        .memberType(ImConstants.MEMBER_TYPE_HUMAN)
                        .memberId(USER_ID)
                        .displayName("Alice")
                        .build()));

        ConversationUpdateRequest r = new ConversationUpdateRequest();
        r.setName("  New Name  ");
        service.updateConversation(CONV_ID, r, USER_ID, TENANT_ID);

        verify(conversationMapper).updateById(any(ImConversation.class));
        verify(imMessageService).sendSystemMessage(eq(CONV_ID), eq(TENANT_ID),
                eq("system"), anyString(), any(), any());
    }

    @Test
    void updateConversation_blankNameNoOp() {
        when(conversationMapper.selectOne(any())).thenReturn(conv(CONV_ID, ImConstants.TYPE_GROUP, USER_ID));
        when(memberMapper.findMember(CONV_ID, ImConstants.MEMBER_TYPE_HUMAN, USER_ID, TENANT_ID))
                .thenReturn(new ImConversationMember());
        ConversationUpdateRequest r = new ConversationUpdateRequest();
        r.setName("   ");
        service.updateConversation(CONV_ID, r, USER_ID, TENANT_ID);
        verify(conversationMapper, never()).updateById(any(ImConversation.class));
    }

    // =================== Misc ===================

    @Test
    void hideConversation_delegatesToMapper() {
        service.hideConversation(CONV_ID, USER_ID, TENANT_ID);
        verify(memberMapper).hideConversation(CONV_ID, ImConstants.MEMBER_TYPE_HUMAN, USER_ID, TENANT_ID);
    }

    @Test
    void getMembers_delegatesToMapper() {
        when(memberMapper.findMembersWithInfo(CONV_ID, TENANT_ID))
                .thenReturn(new ArrayList<>());
        service.getMembers(CONV_ID, TENANT_ID);
        verify(memberMapper).findMembersWithInfo(CONV_ID, TENANT_ID);
    }

    @Test
    void getById_delegatesToMapper() {
        ImConversation c = conv(CONV_ID, ImConstants.TYPE_GROUP, USER_ID);
        when(conversationMapper.selectOne(any())).thenReturn(c);
        assertThat(service.getById(CONV_ID, TENANT_ID)).isSameAs(c);
    }

    @Test
    void findByBoundRecord_delegatesToMapper() {
        ImConversation c = conv(CONV_ID, ImConstants.TYPE_OBJECT, USER_ID);
        when(conversationMapper.selectOne(any())).thenReturn(c);
        assertThat(service.findByBoundRecord("crm_lead", 1L, TENANT_ID)).isSameAs(c);
    }

    @Test
    void createAndBuildListItem_returnsItemWithZeroUnread() {
        ConversationCreateRequest r = req(ImConstants.TYPE_GROUP);
        r.setName("g");
        r.setMemberIds(List.of());

        ConversationListItem item = service.createAndBuildListItem(r, USER_ID, TENANT_ID);

        assertThat(item.getUnreadCount()).isEqualTo(0L);
        assertThat(item.getMemberCount()).isEqualTo(1);
        assertThat(item.getPinned()).isFalse();
    }
}
