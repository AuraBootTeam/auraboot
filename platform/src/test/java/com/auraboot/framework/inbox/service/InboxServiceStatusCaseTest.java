package com.auraboot.framework.inbox.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.auraboot.framework.inbox.model.InboxItem;
import com.auraboot.framework.inbox.mapper.InboxItemMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Contract: listByUser's status filter is case-insensitive. Rows are stored
 * lowercase; the historic uppercase Javadoc form ("PENDING") silently matched
 * 0 rows — the root cause of the inbox_empty fixture dismissing nothing.
 */
class InboxServiceStatusCaseTest {

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedStatusValues(String status) {
        // Lambda column resolution requires the MP TableInfo cache.
        TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new MybatisConfiguration(), ""), InboxItem.class);
        InboxItemMapper mapper = mock(InboxItemMapper.class);
        IPage<InboxItem> page = mock(IPage.class);
        when(page.getRecords()).thenReturn(List.of());
        when(mapper.selectPage(any(), any())).thenReturn(page);

        InboxServiceImpl service = new InboxServiceImpl(mapper);
        service.listByUser(1L, 2L, (String) null, status, 1, 20);

        ArgumentCaptor<LambdaQueryWrapper<InboxItem>> captor =
                ArgumentCaptor.forClass((Class) LambdaQueryWrapper.class);
        verify(mapper).selectPage(any(), captor.capture());
        LambdaQueryWrapper<InboxItem> w = captor.getValue();
        // Params materialize lazily on SQL rendering in this MP version.
        w.getSqlSegment();
        return w.getParamNameValuePairs();
    }

    @Test
    void statusFilterIsNormalizedToLowercase() {
        Map<String, Object> values = capturedStatusValues("PENDING");
        assertTrue(values.containsValue("pending"),
                "uppercase PENDING must query the stored lowercase form, got " + values);
    }

    @Test
    void statusFilterAcceptsLowercaseAsIs() {
        Map<String, Object> values = capturedStatusValues("pending");
        assertTrue(values.containsValue("pending"), "lowercase passthrough, got " + values);
    }

    @Test
    void blankStatusAddsNoStatusCondition() {
        Map<String, Object> values = capturedStatusValues("  ");
        assertFalse(values.containsValue("pending") || values.containsValue("PENDING"),
                "blank status must not filter, got " + values);
    }
}
