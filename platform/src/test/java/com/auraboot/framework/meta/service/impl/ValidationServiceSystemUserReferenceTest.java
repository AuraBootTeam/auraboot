package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.FieldValidationResult;
import com.auraboot.framework.meta.dto.ValidationContext;
import com.auraboot.framework.user.dto.UserSearchDTO;
import com.auraboot.framework.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ValidationServiceSystemUserReferenceTest {

    @Mock
    private UserService userService;

    private ValidationServiceImpl validation;
    private FieldDefinition ownerField;

    @BeforeEach
    void setUp() {
        validation = new ValidationServiceImpl(null, userService);
        ownerField = FieldDefinition.builder()
                .code("owner")
                .name("Owner")
                .dataType("reference")
                .refTarget(FieldDefinition.RefTarget.builder().targetEntity("sys_user").build())
                .build();
        MetaContext.setContext(17L, 23L, null, "test");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void acceptsActiveUserFromCurrentTenant() {
        when(userService.findInTenantByPid(17L, "user-in-tenant"))
                .thenReturn(UserSearchDTO.builder().pid("user-in-tenant").build());

        FieldValidationResult result = validation.validateField(
                ownerField, "user-in-tenant", ValidationContext.CREATE);

        assertTrue(result.isValid(), result.getErrors().toString());
    }

    @Test
    void rejectsUserOutsideCurrentTenant() {
        when(userService.findInTenantByPid(17L, "user-from-other-tenant")).thenReturn(null);

        FieldValidationResult result = validation.validateField(
                ownerField, "user-from-other-tenant", ValidationContext.CREATE);

        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream().anyMatch(error -> error.contains("current tenant")));
    }
}
