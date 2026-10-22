package com.auraboot.framework.integration;

import com.auraboot.framework.im.dto.ConversationCreateRequest;
import com.auraboot.framework.im.mapper.ImConversationMapper;
import com.auraboot.framework.im.model.ImConversation;
import com.auraboot.framework.im.model.ImConstants;
import com.auraboot.framework.im.service.ImConversationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-DB contract for {@code ab_im_conversation.pid} (S0, added by V20260713000000).
 *
 * <p>The column is NOT NULL with a generating DEFAULT rather than a bare NOT NULL, because the
 * table is seeded from raw JDBC and SQL fixtures that predate pid and know nothing about it.
 * That design only holds if MyBatis-Plus really omits a null pid from the INSERT statement so
 * the default can fire — if it ever wrote an explicit NULL instead, every such fixture would
 * start failing on the NOT NULL constraint. These tests pin that behaviour down rather than
 * assuming it.
 */
@Transactional
class ImConversationPidIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ImConversationService conversationService;
    @Autowired
    private ImConversationMapper conversationMapper;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("service-created conversation carries an application-generated pid")
    void serviceCreateAssignsPid() {
        ConversationCreateRequest request = new ConversationCreateRequest();
        request.setType(ImConstants.TYPE_GROUP);
        request.setName("pid-service-" + System.nanoTime());

        ImConversation created = conversationService.create(request, testUser.getId(), testTenant.getId());

        assertThat(created.getPid()).isNotBlank();
        assertThat(created.getPid()).hasSize(26);

        String persisted = jdbc.queryForObject(
                "SELECT pid FROM ab_im_conversation WHERE id = ?", String.class, created.getId());
        assertThat(persisted).isEqualTo(created.getPid());
    }

    @Test
    @DisplayName("mapper insert without pid still gets one — MyBatis-Plus omits the null, the column default fires")
    void mapperInsertWithoutPidFallsBackToColumnDefault() {
        ImConversation conv = new ImConversation();
        conv.setTenantId(testTenant.getId());
        conv.setType(ImConstants.TYPE_GROUP);
        conv.setName("pid-mapper-" + System.nanoTime());
        conv.setMaxSeq(0L);
        conv.setCreatedAt(Instant.now());
        conv.setUpdatedAt(Instant.now());

        conversationMapper.insert(conv);

        String persisted = jdbc.queryForObject(
                "SELECT pid FROM ab_im_conversation WHERE id = ?", String.class, conv.getId());
        assertThat(persisted).isNotBlank();
        assertThat(persisted).hasSize(26);
    }

    @Test
    @DisplayName("raw SQL insert without pid still gets one — covers the JDBC fixtures and E2E seeds")
    void rawSqlInsertWithoutPidFallsBackToColumnDefault() {
        Long id = jdbc.queryForObject(
                "INSERT INTO ab_im_conversation (tenant_id, type, name) VALUES (?, 'group', ?) RETURNING id",
                Long.class, testTenant.getId(), "pid-rawsql-" + System.nanoTime());

        String persisted = jdbc.queryForObject(
                "SELECT pid FROM ab_im_conversation WHERE id = ?", String.class, id);
        assertThat(persisted).isNotBlank();
        assertThat(persisted).hasSize(26);
    }

    @Test
    @DisplayName("pid is unique across conversations regardless of which insert path created them")
    void pidIsUniqueAcrossInsertPaths() {
        ConversationCreateRequest request = new ConversationCreateRequest();
        request.setType(ImConstants.TYPE_GROUP);
        request.setName("pid-uniq-service-" + System.nanoTime());
        ImConversation viaService = conversationService.create(request, testUser.getId(), testTenant.getId());

        ImConversation viaMapper = new ImConversation();
        viaMapper.setTenantId(testTenant.getId());
        viaMapper.setType(ImConstants.TYPE_GROUP);
        viaMapper.setName("pid-uniq-mapper-" + System.nanoTime());
        viaMapper.setMaxSeq(0L);
        viaMapper.setCreatedAt(Instant.now());
        viaMapper.setUpdatedAt(Instant.now());
        conversationMapper.insert(viaMapper);

        Long viaSqlId = jdbc.queryForObject(
                "INSERT INTO ab_im_conversation (tenant_id, type, name) VALUES (?, 'group', ?) RETURNING id",
                Long.class, testTenant.getId(), "pid-uniq-rawsql-" + System.nanoTime());

        List<String> pids = jdbc.queryForList(
                "SELECT pid FROM ab_im_conversation WHERE id IN (?, ?, ?)",
                String.class, viaService.getId(), viaMapper.getId(), viaSqlId);

        assertThat(pids).hasSize(3);
        assertThat(pids).doesNotContainNull();
        assertThat(pids).doesNotHaveDuplicates();
    }
}
