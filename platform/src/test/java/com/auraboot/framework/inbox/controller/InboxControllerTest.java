package com.auraboot.framework.inbox.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.inbox.dto.InboxItemResponse;
import com.auraboot.framework.inbox.model.InboxItem;
import com.auraboot.framework.inbox.service.InboxService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InboxControllerTest {

    @Mock
    private InboxService inboxService;

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void listMapsEntityPageToInboxResponsePage() {
        MetaContext.setCurrentUserId(101L);
        MetaContext.setCurrentTenantId(202L);
        InboxItem item = buildInboxItem();
        Page<InboxItem> page = new Page<>(1, 20);
        page.setTotal(1);
        page.setRecords(List.of(item));
        when(inboxService.listByUser(101L, 202L, "approval", "pending", 1, 20)).thenReturn(page);

        InboxController controller = new InboxController(inboxService);
        ApiResponse<IPage<InboxItemResponse>> response = controller.list("approval", "pending", 1, 20);

        assertTrue(response.isSuccess());
        assertNotNull(response.getData());
        assertEquals(1L, response.getData().getTotal());
        assertEquals(1, response.getData().getRecords().size());

        InboxItemResponse record = response.getData().getRecords().get(0);
        assertEquals("Call customer before Friday", record.getSummary());
        assertEquals("crm_activity", record.getSourceModel());
        assertEquals("2002", record.getSourceRecordId());
        assertEquals("Acme Corp", record.getCardData().get("accountName"));
        assertEquals("crm_activity", record.getModelCode(), "legacy field must remain available");
        assertEquals("Follow up quote", record.getTitle());

        verify(inboxService).listByUser(101L, 202L, "approval", "pending", 1, 20);
    }

    @Test
    void getItemReturnsMappedDtoWithLegacyAndMobileFields() {
        MetaContext.setCurrentUserId(101L);
        MetaContext.setCurrentTenantId(202L);
        InboxItem item = buildInboxItem();
        when(inboxService.getItem(1001L, 101L, 202L)).thenReturn(item);

        InboxController controller = new InboxController(inboxService);
        ApiResponse<InboxItemResponse> response = controller.getItem(1001L);

        assertTrue(response.isSuccess());
        assertNotNull(response.getData());
        assertEquals("Call customer before Friday", response.getData().getSummary());
        assertEquals("crm_activity", response.getData().getSourceModel());
        assertEquals("2002", response.getData().getSourceRecordId());
        assertEquals("{\"accountName\":\"Acme Corp\",\"nextStep\":\"Call procurement owner\"}", response.getData().getCardPayload());
        assertEquals(Map.of("accountName", "Acme Corp", "nextStep", "Call procurement owner"), response.getData().getCardData());

        verify(inboxService).getItem(1001L, 101L, 202L);
    }

    private InboxItem buildInboxItem() {
        InboxItem item = new InboxItem();
        item.setId(1001L);
        item.setItemType("approval");
        item.setTitle("Follow up quote");
        item.setSubtitle("Call customer before Friday");
        item.setStatus("pending");
        item.setPriority("high");
        item.setSourceType("command");
        item.setSourceId("cmd-01");
        item.setModelCode("crm_activity");
        item.setRecordId(2002L);
        item.setCardPayload("{\"accountName\":\"Acme Corp\",\"nextStep\":\"Call procurement owner\"}");
        item.setDeepLink("auraboot://object/crm_activity/2002");
        item.setIsRead(false);
        return item;
    }
}
