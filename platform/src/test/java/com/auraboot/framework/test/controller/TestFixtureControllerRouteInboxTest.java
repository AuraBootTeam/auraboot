package com.auraboot.framework.test.controller;

import com.auraboot.framework.inbox.model.InboxItem;
import com.auraboot.framework.inbox.service.InboxService;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.test.dto.FixtureResult;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TestFixtureControllerRouteInboxTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void inboxRouteFixtureCreatesDeepLinkedInboxCardWithActions() throws Exception {
        TestFixtureController controller = new TestFixtureController();
        ApplicationContext applicationContext = mock(ApplicationContext.class);
        InboxService inboxService = mock(InboxService.class);
        DynamicDataService dynamicDataService = mock(DynamicDataService.class);
        TenantService tenantService = mock(TenantService.class);
        UserService userService = mock(UserService.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);

        Tenant tenant = new Tenant();
        tenant.setId(100L);
        User user = new User();
        user.setId(200L);
        user.setPid("01KUSER00000000000000000001");
        user.setEmail("e2e@test.local");

        AtomicLong inboxIds = new AtomicLong(7000L);
        when(applicationContext.containsBean("inboxService")).thenReturn(true);
        when(applicationContext.getBean("inboxService")).thenReturn(inboxService);
        when(tenantService.findByName("e2e_test")).thenReturn(tenant);
        when(userService.findByEmail("e2e@test.local")).thenReturn(user);
        when(dynamicDataService.create(eq("e2et_order"), anyMap()))
                .thenReturn(Map.of("pid", "01KROUTE000000000000000001"));
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any()))
                .thenReturn(9001L);
        when(inboxService.createItem(any(InboxItem.class))).thenAnswer(invocation -> {
            InboxItem item = invocation.getArgument(0);
            item.setId(inboxIds.getAndIncrement());
            return item;
        });

        ReflectionTestUtils.setField(controller, "applicationContext", applicationContext);
        ReflectionTestUtils.setField(controller, "dynamicDataService", dynamicDataService);
        ReflectionTestUtils.setField(controller, "tenantService", tenantService);
        ReflectionTestUtils.setField(controller, "userService", userService);
        ReflectionTestUtils.setField(controller, "jdbcTemplate", jdbcTemplate);

        FixtureResult result = ReflectionTestUtils.invokeMethod(
                controller,
                "createRouteBearingInboxFixture",
                "route_run",
                Map.of(
                        "count", 1,
                        "modelCode", "e2et_order",
                        "action", "open",
                        "actionLabel", "Open seeded order",
                        "cardData", Map.of("accountName", "RouteCo")
                ),
                null
        );

        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals("inbox_route", result.getFixtureName());
        assertEquals(1, result.getRecordsCreated());
        assertEquals(List.of("7000"), result.getRecordIds());
        assertEquals("e2et_order", result.getMetadata().get("modelCode"));
        assertEquals(List.of("01KROUTE000000000000000001"), result.getMetadata().get("routeRecordIds"));

        ArgumentCaptor<InboxItem> itemCaptor = ArgumentCaptor.forClass(InboxItem.class);
        verify(inboxService).createItem(itemCaptor.capture());
        InboxItem item = itemCaptor.getValue();
        assertEquals(100L, item.getTenantId());
        assertEquals(200L, item.getUserId());
        assertEquals("task", item.getItemType());
        assertEquals("normal", item.getPriority());
        assertEquals("pending", item.getStatus());
        assertEquals("e2et_order", item.getModelCode());
        assertEquals(9001L, item.getRecordId());
        assertEquals("auraboot://object/e2et_order/01KROUTE000000000000000001", item.getDeepLink());
        assertFalse(item.getIsRead());

        Map<String, Object> cardData = MAPPER.readValue(item.getCardPayload(), new TypeReference<>() {});
        assertEquals("RouteCo", cardData.get("accountName"));
        assertEquals("e2et_order", cardData.get("modelCode"));
        assertEquals("01KROUTE000000000000000001", cardData.get("recordId"));
        assertEquals(item.getDeepLink(), cardData.get("deepLink"));

        List<?> actions = (List<?>) cardData.get("actions");
        assertEquals(1, actions.size());
        Map<?, ?> action = (Map<?, ?>) actions.get(0);
        assertEquals("open", action.get("action"));
        assertEquals("Open seeded order", action.get("label"));
        assertEquals("primary", action.get("style"));
    }
}
