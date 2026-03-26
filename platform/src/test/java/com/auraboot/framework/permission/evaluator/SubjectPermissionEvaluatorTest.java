package com.auraboot.framework.permission.evaluator;

import com.auraboot.framework.permission.entity.SubjectPermission;
import com.auraboot.framework.permission.mapper.SubjectPermissionMapper;
import com.auraboot.framework.permission.service.UserPermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SubjectPermissionEvaluator Unit Test
 * 
 * Tests the visibility evaluation logic with AND/OR logic groups.
 * 
 * @author AuraBoot Platform
 * @since V4
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SubjectPermissionEvaluator Unit Tests")
class SubjectPermissionEvaluatorTest {
    
    @Mock
    private SubjectPermissionMapper subjectPermissionMapper;
    
    @Mock
    private UserPermissionService userPermissionService;
    
    @InjectMocks
    private SubjectPermissionEvaluator evaluator;
    
    private Set<Long> userPermissions;
    
    @BeforeEach
    void setUp() {
        // User has permissions: 1, 2, 3
        userPermissions = Set.of(1L, 2L, 3L);
    }
    
    @Test
    @DisplayName("Should return true when no declarations exist (default visible)")
    void testEvaluate_NoDeclarations() {
        // Given
        when(subjectPermissionMapper.findBySubject("menu", 1L))
            .thenReturn(new ArrayList<>());
        
        // When
        boolean result = evaluator.evaluate("menu", 1L, 1L);
        
        // Then
        assertThat(result).isTrue();
    }
    
    @Test
    @DisplayName("Should evaluate AND logic group correctly - all satisfied")
    void testEvaluate_AND_AllSatisfied() {
        // Given
        List<SubjectPermission> declarations = List.of(
            createDeclaration(1L, 1, "and", false, 1),  // User has permission 1
            createDeclaration(2L, 1, "and", false, 2)   // User has permission 2
        );
        
        when(subjectPermissionMapper.findBySubject("menu", 1L)).thenReturn(declarations);
        when(userPermissionService.getUserPermissionIds(1L)).thenReturn(userPermissions);
        
        // When
        boolean result = evaluator.evaluate("menu", 1L, 1L);
        
        // Then
        assertThat(result).isTrue();
    }
    
    @Test
    @DisplayName("Should evaluate AND logic group correctly - one not satisfied")
    void testEvaluate_AND_OneNotSatisfied() {
        // Given
        List<SubjectPermission> declarations = List.of(
            createDeclaration(1L, 1, "and", false, 1),  // User has permission 1
            createDeclaration(99L, 1, "and", false, 2)  // User does NOT have permission 99
        );
        
        when(subjectPermissionMapper.findBySubject("menu", 1L)).thenReturn(declarations);
        when(userPermissionService.getUserPermissionIds(1L)).thenReturn(userPermissions);
        
        // When
        boolean result = evaluator.evaluate("menu", 1L, 1L);
        
        // Then
        assertThat(result).isFalse();
    }
    
    @Test
    @DisplayName("Should evaluate OR logic group correctly - one satisfied")
    void testEvaluate_OR_OneSatisfied() {
        // Given
        List<SubjectPermission> declarations = List.of(
            createDeclaration(1L, 1, "OR", false, 1),   // User has permission 1
            createDeclaration(99L, 1, "OR", false, 2)   // User does NOT have permission 99
        );
        
        when(subjectPermissionMapper.findBySubject("menu", 1L)).thenReturn(declarations);
        when(userPermissionService.getUserPermissionIds(1L)).thenReturn(userPermissions);
        
        // When
        boolean result = evaluator.evaluate("menu", 1L, 1L);
        
        // Then
        assertThat(result).isTrue();
    }
    
    @Test
    @DisplayName("Should evaluate OR logic group correctly - none satisfied")
    void testEvaluate_OR_NoneSatisfied() {
        // Given
        List<SubjectPermission> declarations = List.of(
            createDeclaration(98L, 1, "OR", false, 1),  // User does NOT have permission 98
            createDeclaration(99L, 1, "OR", false, 2)   // User does NOT have permission 99
        );
        
        when(subjectPermissionMapper.findBySubject("menu", 1L)).thenReturn(declarations);
        when(userPermissionService.getUserPermissionIds(1L)).thenReturn(userPermissions);
        
        // When
        boolean result = evaluator.evaluate("menu", 1L, 1L);
        
        // Then
        assertThat(result).isFalse();
    }
    
    @Test
    @DisplayName("Should evaluate is_negated correctly")
    void testEvaluate_IsNegated() {
        // Given
        List<SubjectPermission> declarations = List.of(
            createDeclaration(1L, 1, "and", true, 1),   // User has permission 1, but negated
            createDeclaration(99L, 1, "and", true, 2)   // User does NOT have permission 99, negated
        );
        
        when(subjectPermissionMapper.findBySubject("menu", 1L)).thenReturn(declarations);
        when(userPermissionService.getUserPermissionIds(1L)).thenReturn(userPermissions);
        
        // When
        boolean result = evaluator.evaluate("menu", 1L, 1L);
        
        // Then
        // Permission 1: has=true, negated=true -> result=false
        // Permission 99: has=false, negated=true -> result=true
        // AND logic: false AND true = false
        assertThat(result).isFalse();
    }
    
    @Test
    @DisplayName("Should evaluate multiple logic groups with OR between groups")
    void testEvaluate_MultipleLogicGroups() {
        // Given
        List<SubjectPermission> declarations = List.of(
            // Group 1 (AND): NOT satisfied
            createDeclaration(1L, 1, "and", false, 1),
            createDeclaration(99L, 1, "and", false, 2),
            
            // Group 2 (AND): satisfied
            createDeclaration(2L, 2, "and", false, 1),
            createDeclaration(3L, 2, "and", false, 2)
        );
        
        when(subjectPermissionMapper.findBySubject("menu", 1L)).thenReturn(declarations);
        when(userPermissionService.getUserPermissionIds(1L)).thenReturn(userPermissions);
        
        // When
        boolean result = evaluator.evaluate("menu", 1L, 1L);
        
        // Then
        // Group 1: false (because permission 99 not satisfied)
        // Group 2: true (both permissions satisfied)
        // Groups OR: false OR true = true
        assertThat(result).isTrue();
    }
    
    @Test
    @DisplayName("Should deny visibility when logic group has inconsistent types (fail-secure)")
    void testEvaluate_InconsistentLogicType() {
        // Given
        List<SubjectPermission> declarations = List.of(
            createDeclaration(1L, 1, "and", false, 1),
            createDeclaration(2L, 1, "OR", false, 2)  // Inconsistent!
        );

        when(subjectPermissionMapper.findBySubject("menu", 1L)).thenReturn(declarations);
        when(userPermissionService.getUserPermissionIds(1L)).thenReturn(userPermissions);

        // When: fail-secure — inconsistent data denies visibility (returns false)
        boolean result = evaluator.evaluate("menu", 1L, 1L);

        // Then
        assertThat(result).isFalse();
    }
    
    @Test
    @DisplayName("Should batch evaluate multiple subjects")
    void testBatchEvaluate_Success() {
        // Given
        List<Long> subjectIds = List.of(1L, 2L, 3L);
        
        // Subject 1: has declarations, visible
        SubjectPermission decl1 = createDeclaration(1L, 1, "and", false, 1);
        decl1.setSubjectId(1L);
        
        // Subject 2: has declarations, not visible
        SubjectPermission decl2 = createDeclaration(99L, 1, "and", false, 1);
        decl2.setSubjectId(2L);
        
        // Subject 3: no declarations, default visible
        List<SubjectPermission> allDeclarations = List.of(decl1, decl2);
        
        when(subjectPermissionMapper.findBySubjects("menu", subjectIds))
            .thenReturn(allDeclarations);
        when(userPermissionService.getUserPermissionIds(1L)).thenReturn(userPermissions);
        
        // When
        Map<Long, Boolean> result = evaluator.batchEvaluate("menu", subjectIds, 1L);
        
        // Then
        assertThat(result).hasSize(3);
        assertThat(result.get(1L)).isTrue();   // Has permission 1
        assertThat(result.get(2L)).isFalse();  // Does NOT have permission 99
        assertThat(result.get(3L)).isTrue();   // No declarations, default visible
    }
    
    // Helper method to create test declarations
    private SubjectPermission createDeclaration(
            Long permissionId,
            Integer logicGroup,
            String groupLogicType,
            Boolean isNegated,
            Integer logicOrder) {
        
        SubjectPermission declaration = new SubjectPermission();
        declaration.setSubjectType("menu");
        declaration.setSubjectId(1L);
        declaration.setPermissionId(permissionId);
        declaration.setLogicGroup(logicGroup);
        declaration.setGroupLogicType(groupLogicType);
        declaration.setIsNegated(isNegated);
        declaration.setLogicOrder(logicOrder);
        return declaration;
    }
}
