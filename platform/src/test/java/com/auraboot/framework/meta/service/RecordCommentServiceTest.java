package com.auraboot.framework.meta.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.exception.MetaServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RecordCommentService} record-visibility gating (SEC-20260723-04).
 *
 * <p>When the target model is registered, reading or appending a record's comments must first
 * pass the record's row-ACL (via {@link DynamicDataService#getById}); a caller who cannot view
 * the record must be denied <em>before</em> the comment table is touched. Comments on
 * unregistered (non-model) targets are left ungated.
 */
@ExtendWith(MockitoExtension.class)
class RecordCommentServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private MetaModelService metaModelService;
    @Mock
    private DynamicDataService dynamicDataService;

    @InjectMocks
    private RecordCommentService service;

    @AfterEach
    void clearContext() {
        MetaContext.clear();
    }

    @Test
    @DisplayName("listComments on a registered model denies (before any query) when the record is not visible")
    void listComments_notVisible_deniesBeforeQuery() {
        when(metaModelService.getModelDefinition("crm_lead"))
                .thenReturn(Optional.of(mock(ModelDefinition.class)));
        when(dynamicDataService.getById("crm_lead", "rec-1"))
                .thenThrow(new MetaServiceException(
                        "Access denied: you do not have permission to view this record"));

        assertThatThrownBy(() -> service.listComments("crm_lead", "rec-1"))
                .isInstanceOf(MetaServiceException.class);
        // The comment table must never be queried once visibility is denied.
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("addComment on a registered model denies (before insert) when the record is not visible")
    void addComment_notVisible_deniesBeforeInsert() {
        when(metaModelService.getModelDefinition("crm_lead"))
                .thenReturn(Optional.of(mock(ModelDefinition.class)));
        when(dynamicDataService.getById("crm_lead", "rec-1"))
                .thenThrow(new MetaServiceException("Access denied"));

        assertThatThrownBy(() -> service.addComment("crm_lead", "rec-1", "secret comment", null))
                .isInstanceOf(MetaServiceException.class);
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("comments on an unregistered (non-model) target skip the visibility gate")
    void listComments_unregisteredModel_skipsGate() {
        when(metaModelService.getModelDefinition("arbitrary_target"))
                .thenReturn(Optional.empty());
        MetaContext.setContext(1L, 1L, "user-pid", "user");

        service.listComments("arbitrary_target", "no-record");

        // Gate is skipped: getById is never consulted for a non-model target.
        verify(dynamicDataService, never()).getById(anyString(), anyString());
    }
}
