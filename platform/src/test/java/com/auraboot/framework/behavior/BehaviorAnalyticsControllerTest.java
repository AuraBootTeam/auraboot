package com.auraboot.framework.behavior;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.behavior.controller.BehaviorAnalyticsController;
import com.auraboot.framework.behavior.dto.BehaviorAnalyticsRecords;
import com.auraboot.framework.behavior.dto.BehaviorEventCount;
import com.auraboot.framework.behavior.dto.BehaviorOverview;
import com.auraboot.framework.behavior.mapper.BehaviorEventMapper;
import com.auraboot.framework.common.dto.ApiResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Plain unit test for BehaviorAnalyticsController — no Spring context, no database.
 * Uses Mockito mockStatic to stub MetaContext.getCurrentTenantId().
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BehaviorAnalyticsController")
class BehaviorAnalyticsControllerTest {

    @Mock
    private BehaviorEventMapper behaviorEventMapper;

    @InjectMocks
    private BehaviorAnalyticsController controller;

    private MockedStatic<MetaContext> metaContextMock;

    private static final Long TENANT_ID = 42L;

    @BeforeEach
    void setUp() {
        metaContextMock = Mockito.mockStatic(MetaContext.class);
        metaContextMock.when(MetaContext::getCurrentTenantId).thenReturn(TENANT_ID);
    }

    @AfterEach
    void tearDown() {
        if (metaContextMock != null) metaContextMock.close();
    }

    @Test
    @DisplayName("overview() wraps result in ApiResponse with code '0' and records list of size 1")
    void overview_returns_api_response_with_records_envelope() {
        BehaviorOverview overview = new BehaviorOverview();
        overview.setTotalEvents(100L);
        overview.setPageViews(80L);
        overview.setUniqueVisitors(5L);
        overview.setSessions(10L);

        when(behaviorEventMapper.overview(TENANT_ID)).thenReturn(overview);

        ApiResponse<BehaviorAnalyticsRecords<BehaviorOverview>> resp = controller.overview();

        assertEquals("0", resp.getCode(), "Response code must be '0' (OK)");
        assertNotNull(resp.getData(), "Response data must not be null");
        List<BehaviorOverview> records = resp.getData().getRecords();
        assertNotNull(records, "records must not be null");
        assertEquals(1, records.size(), "overview records must have exactly 1 element");
        assertSame(overview, records.get(0), "records[0] must be the stubbed BehaviorOverview");
    }

    @Test
    @DisplayName("topEvents() wraps result in ApiResponse with code '0' and records of correct size")
    void topEvents_returns_api_response_with_records_envelope() {
        BehaviorEventCount e1 = new BehaviorEventCount();
        e1.setEventName("click");
        e1.setCount(20L);

        BehaviorEventCount e2 = new BehaviorEventCount();
        e2.setEventName("page_view");
        e2.setCount(50L);

        when(behaviorEventMapper.topEvents(TENANT_ID)).thenReturn(List.of(e1, e2));

        ApiResponse<BehaviorAnalyticsRecords<BehaviorEventCount>> resp = controller.topEvents();

        assertEquals("0", resp.getCode(), "Response code must be '0' (OK)");
        assertNotNull(resp.getData(), "Response data must not be null");
        List<BehaviorEventCount> records = resp.getData().getRecords();
        assertNotNull(records, "records must not be null");
        assertEquals(2, records.size(), "topEvents records must have exactly 2 elements");
    }
}
