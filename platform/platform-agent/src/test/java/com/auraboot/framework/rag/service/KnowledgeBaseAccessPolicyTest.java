package com.auraboot.framework.rag.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseAccessPolicyTest {

    @Mock
    private JdbcTemplate jdbc;

    private KnowledgeBaseAccessPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new KnowledgeBaseAccessPolicy(jdbc);
        MetaContext.setContext(7L, 11L, "user-pid", "reader", Set.of(31L));
        MetaContext.setMemberId(21L);
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void automaticSelectionOnlyReturnsActiveChunkedTenantKnowledgeBases() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of(
                        "pid", "kb-visible",
                        "visibility", "tenant",
                        "created_by", 99L)));

        assertThat(policy.resolveReadable(7L, null))
                .containsExactly("kb-visible");
        verify(jdbc).queryForList(
                contains("chunk_count > 0"),
                any(Object[].class));
    }

    @Test
    void explicitRequestFailsClosedWithoutRevealingWhichPidIsMissing() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of(
                        "pid", "kb-one",
                        "visibility", "tenant",
                        "created_by", 99L)));

        assertThatThrownBy(() ->
                policy.resolveReadable(7L, List.of("kb-one", "kb-outside")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("One or more knowledge bases are not active or accessible");
    }

    @Test
    void manageGrantAlsoAuthorizesReadForRestrictedKnowledgeBase() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of(
                        "pid", "kb-restricted",
                        "visibility", "restricted",
                        "created_by", 99L)));
        when(jdbc.queryForList(
                anyString(),
                eq(String.class),
                any(Object[].class)))
                .thenReturn(List.of("kb-restricted"));

        assertThat(policy.resolveReadable(7L, List.of("kb-restricted")))
                .containsExactly("kb-restricted");
        verify(jdbc).queryForList(
                contains("permission IN ('read', 'manage')"),
                eq(String.class),
                any(Object[].class));
    }

    @Test
    void restrictedKnowledgeBaseWithoutOwnerOrGrantIsForbidden() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of(
                        "pid", "kb-restricted",
                        "visibility", "restricted",
                        "created_by", 99L)));
        when(jdbc.queryForList(
                anyString(),
                eq(String.class),
                any(Object[].class)))
                .thenReturn(List.of());

        assertThatThrownBy(() ->
                policy.resolveReadable(7L, List.of("kb-restricted")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void tenantVisibleKnowledgeBaseStillRequiresAnAuthenticatedActor() {
        MetaContext.clear();
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of(
                        "pid", "kb-visible",
                        "visibility", "tenant",
                        "created_by", 99L)));

        assertThatThrownBy(() ->
                policy.resolveReadable(7L, List.of("kb-visible")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void metadataListingIncludesEmptyDisabledRowsAndFiltersUnauthorizedPrivateRows() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(
                        Map.of(
                                "pid", "kb-disabled",
                                "visibility", "tenant",
                                "created_by", 99L),
                        Map.of(
                                "pid", "kb-private",
                                "visibility", "private",
                                "created_by", 99L)));
        when(jdbc.queryForList(
                anyString(),
                eq(String.class),
                any(Object[].class)))
                .thenReturn(List.of());

        assertThat(policy.listReadableMetadata(7L))
                .containsExactly("kb-disabled");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(sql.capture(), any(Object[].class));
        assertThat(sql.getValue())
                .doesNotContain("status = 'active'")
                .doesNotContain("chunk_count > 0");
    }

    @Test
    void readGrantDoesNotAuthorizeRestrictedKnowledgeBaseManagement() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of(
                        "pid", "kb-restricted",
                        "visibility", "restricted",
                        "created_by", 99L)));
        when(jdbc.queryForList(
                anyString(),
                eq(String.class),
                any(Object[].class)))
                .thenReturn(List.of());

        assertThatThrownBy(() ->
                policy.requireManage(7L, "kb-restricted"))
                .isInstanceOf(BusinessException.class);
        verify(jdbc).queryForList(
                contains("permission = 'manage'"),
                eq(String.class),
                any(Object[].class));
    }

    @Test
    void manageGrantAuthorizesRestrictedKnowledgeBaseManagement() {
        when(jdbc.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of(
                        "pid", "kb-restricted",
                        "visibility", "restricted",
                        "created_by", 99L)));
        when(jdbc.queryForList(
                anyString(),
                eq(String.class),
                any(Object[].class)))
                .thenReturn(List.of("kb-restricted"));

        policy.requireManage(7L, "kb-restricted");

        verify(jdbc).queryForList(
                contains("permission = 'manage'"),
                eq(String.class),
                any(Object[].class));
    }
}
