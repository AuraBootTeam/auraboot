package com.auraboot.framework.permission.service.impl;

import com.auraboot.framework.application.exception.DuplicateException;
import com.auraboot.framework.application.exception.ResourceNotFoundException;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.permission.converter.SubjectPermissionConverter;
import com.auraboot.framework.permission.dto.SubjectPermissionCreateRequest;
import com.auraboot.framework.permission.dto.SubjectPermissionDTO;
import com.auraboot.framework.permission.entity.SubjectPermission;
import com.auraboot.framework.permission.evaluator.SubjectPermissionEvaluator;
import com.auraboot.framework.permission.mapper.SubjectPermissionMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SubjectPermissionServiceImpl}.
 */
@ExtendWith(MockitoExtension.class)
class SubjectPermissionServiceImplTest {

    @Mock
    private SubjectPermissionMapper subjectPermissionMapper;

    @Mock
    private SubjectPermissionConverter subjectPermissionConverter;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private SubjectPermissionEvaluator evaluator;

    @Mock
    private Cache cache;

    @InjectMocks
    private SubjectPermissionServiceImpl service;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(100L, 1L, "u-pid", "tester");
        lenient().when(cacheManager.getCache("subject-evaluation")).thenReturn(cache);
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    private SubjectPermissionCreateRequest req(String type, Long subjectId, Long permissionId) {
        SubjectPermissionCreateRequest r = new SubjectPermissionCreateRequest();
        r.setSubjectType(type);
        r.setSubjectId(subjectId);
        r.setPermissionId(permissionId);
        r.setLogicGroup(0);
        r.setGroupLogicType("or");
        return r;
    }

    @Test
    void addPermissionValidatesEmptySubjectType() {
        SubjectPermissionCreateRequest r = req("MENU", 10L, 50L);
        r.setSubjectType("");

        assertThatThrownBy(() -> service.addPermission(r)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addPermissionValidatesNullSubjectId() {
        SubjectPermissionCreateRequest r = req("MENU", null, 50L);

        assertThatThrownBy(() -> service.addPermission(r)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addPermissionValidatesNullPermissionId() {
        SubjectPermissionCreateRequest r = req("MENU", 10L, null);

        assertThatThrownBy(() -> service.addPermission(r)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addPermissionValidatesNullLogicGroup() {
        SubjectPermissionCreateRequest r = req("MENU", 10L, 50L);
        r.setLogicGroup(null);

        assertThatThrownBy(() -> service.addPermission(r)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addPermissionValidatesEmptyLogicType() {
        SubjectPermissionCreateRequest r = req("MENU", 10L, 50L);
        r.setGroupLogicType("");

        assertThatThrownBy(() -> service.addPermission(r)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void addPermissionValidatesUnknownLogicType() {
        SubjectPermissionCreateRequest r = req("MENU", 10L, 50L);
        r.setGroupLogicType("XOR");

        assertThatThrownBy(() -> service.addPermission(r))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AND or OR");
    }

    @Test
    void addPermissionThrowsOnDuplicate() {
        SubjectPermissionCreateRequest r = req("MENU", 10L, 50L);
        when(subjectPermissionMapper.countByDeclaration(eq("MENU"), eq(10L), anyString(), eq(50L), eq(0), isNull()))
                .thenReturn(1);

        assertThatThrownBy(() -> service.addPermission(r)).isInstanceOf(DuplicateException.class);
    }

    @Test
    void addPermissionThrowsOnInconsistentLogicGroup() {
        SubjectPermissionCreateRequest r = req("MENU", 10L, 50L);
        r.setGroupLogicType("AND");
        when(subjectPermissionMapper.countByDeclaration(anyString(), anyLong(), anyString(), anyLong(), anyInt(), isNull()))
                .thenReturn(0);
        SubjectPermission existing = new SubjectPermission();
        existing.setGroupLogicType("or");
        when(subjectPermissionMapper.findByLogicGroup("MENU", 10L, 0)).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.addPermission(r))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Logic group");
    }

    @Test
    void addPermissionInsertsAndEvictsCache() {
        SubjectPermissionCreateRequest r = req("MENU", 10L, 50L);
        when(subjectPermissionMapper.countByDeclaration(anyString(), anyLong(), anyString(), anyLong(), anyInt(), isNull()))
                .thenReturn(0);
        when(subjectPermissionMapper.findByLogicGroup("MENU", 10L, 0)).thenReturn(List.of());
        SubjectPermission entity = new SubjectPermission();
        when(subjectPermissionConverter.toEntity(r)).thenReturn(entity);
        SubjectPermissionDTO dto = new SubjectPermissionDTO();
        when(subjectPermissionConverter.toDTO(entity)).thenReturn(dto);

        SubjectPermissionDTO result = service.addPermission(r);

        assertThat(result).isSameAs(dto);
        verify(subjectPermissionMapper).insert(entity);
        verify(cache).clear();
    }

    @Test
    void batchAddPermissionsRejectsInconsistentGroupLogicType() {
        SubjectPermissionCreateRequest a = req("MENU", 10L, 50L);
        a.setGroupLogicType("and");
        SubjectPermissionCreateRequest b = req("MENU", 10L, 51L);
        b.setGroupLogicType("or");

        assertThatThrownBy(() -> service.batchAddPermissions("MENU", 10L, List.of(a, b)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inconsistent");
    }

    @Test
    void batchAddPermissionsInsertsWhenConsistent() {
        SubjectPermissionCreateRequest a = req("MENU", 10L, 50L);
        SubjectPermissionCreateRequest b = req("MENU", 10L, 51L);
        when(subjectPermissionMapper.findByLogicGroup("MENU", 10L, 0)).thenReturn(List.of());
        SubjectPermission e1 = new SubjectPermission();
        SubjectPermission e2 = new SubjectPermission();
        when(subjectPermissionConverter.toEntity(a)).thenReturn(e1);
        when(subjectPermissionConverter.toEntity(b)).thenReturn(e2);
        when(subjectPermissionConverter.toDTOList(anyList())).thenReturn(List.of(new SubjectPermissionDTO(), new SubjectPermissionDTO()));

        List<SubjectPermissionDTO> result = service.batchAddPermissions("MENU", 10L, List.of(a, b));

        assertThat(result).hasSize(2);
        verify(subjectPermissionMapper).batchInsert(anyList());
        verify(cache).clear();
    }

    @Test
    void removePermissionThrowsWhenMissing() {
        when(subjectPermissionMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.removePermission(99L)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void removePermissionDeletesAndEvicts() {
        SubjectPermission sp = new SubjectPermission();
        sp.setId(99L);
        sp.setSubjectType("MENU");
        sp.setSubjectId(10L);
        when(subjectPermissionMapper.selectById(99L)).thenReturn(sp);

        service.removePermission(99L);

        verify(subjectPermissionMapper).deleteById(99L);
        verify(cache).clear();
    }

    @Test
    void removeAllPermissionsDelegatesToMapper() {
        when(subjectPermissionMapper.deleteBySubject("MENU", 10L)).thenReturn(3);

        service.removeAllPermissions("MENU", 10L);

        verify(subjectPermissionMapper).deleteBySubject("MENU", 10L);
        verify(cache).clear();
    }

    @Test
    void findBySubjectReturnsList() {
        when(subjectPermissionMapper.findBySubject("MENU", 10L)).thenReturn(List.of(new SubjectPermission()));
        when(subjectPermissionConverter.toDTOList(anyList())).thenReturn(List.of(new SubjectPermissionDTO()));

        assertThat(service.findBySubject("MENU", 10L)).hasSize(1);
    }

    @Test
    void findBySubjectCodeReturnsList() {
        when(subjectPermissionMapper.findBySubjectCode("MENU", "menu.users")).thenReturn(List.of(new SubjectPermission()));
        when(subjectPermissionConverter.toDTOList(anyList())).thenReturn(List.of(new SubjectPermissionDTO()));

        assertThat(service.findBySubjectCode("MENU", "menu.users")).hasSize(1);
    }

    @Test
    void evaluateVisibilityDelegatesToEvaluator() {
        when(evaluator.evaluate("MENU", 10L, 1L)).thenReturn(true);

        assertThat(service.evaluateVisibility("MENU", 10L, 1L)).isTrue();
    }

    @Test
    void batchEvaluateVisibilityDelegatesToEvaluator() {
        when(evaluator.batchEvaluate(eq("MENU"), eq(List.of(10L)), eq(1L))).thenReturn(Map.of(10L, true));

        Map<Long, Boolean> result = service.batchEvaluateVisibility("MENU", List.of(10L), 1L);

        assertThat(result).containsEntry(10L, true);
    }

    @Test
    void evictSubjectEvaluationsClearsCache() {
        service.evictSubjectEvaluations("MENU", 10L);

        verify(cache).clear();
    }

    @Test
    void validateLogicGroupConsistencyReturnsTrueWhenSingleType() {
        when(subjectPermissionMapper.checkLogicGroupConsistency("MENU", 10L, 0)).thenReturn(1);

        assertThat(service.validateLogicGroupConsistency("MENU", 10L, 0)).isTrue();
    }

    @Test
    void validateLogicGroupConsistencyReturnsFalseWhenMultipleTypes() {
        when(subjectPermissionMapper.checkLogicGroupConsistency("MENU", 10L, 0)).thenReturn(2);

        assertThat(service.validateLogicGroupConsistency("MENU", 10L, 0)).isFalse();
    }

    @Test
    void addPermissionNormalizesLogicTypeCase() {
        SubjectPermissionCreateRequest r = req("MENU", 10L, 50L);
        r.setGroupLogicType("AND");
        when(subjectPermissionMapper.countByDeclaration(anyString(), anyLong(), anyString(), anyLong(), anyInt(), isNull()))
                .thenReturn(0);
        when(subjectPermissionMapper.findByLogicGroup("MENU", 10L, 0)).thenReturn(List.of());
        when(subjectPermissionConverter.toEntity(any())).thenReturn(new SubjectPermission());
        when(subjectPermissionConverter.toDTO(any())).thenReturn(new SubjectPermissionDTO());

        service.addPermission(r);

        // Request mutated to lowercase per service contract
        assertThat(r.getGroupLogicType()).isEqualTo("and");
    }
}
