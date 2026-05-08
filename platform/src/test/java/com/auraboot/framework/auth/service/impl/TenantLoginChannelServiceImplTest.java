package com.auraboot.framework.auth.service.impl;

import com.auraboot.framework.auth.dto.ChannelUpdateRequest;
import com.auraboot.framework.auth.entity.TenantLoginChannel;
import com.auraboot.framework.auth.mapper.TenantLoginChannelMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TenantLoginChannelServiceImpl")
class TenantLoginChannelServiceImplTest {

    @Mock
    private TenantLoginChannelMapper channelMapper;

    private TenantLoginChannelServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TenantLoginChannelServiceImpl(channelMapper);
    }

    private TenantLoginChannel ch(String code, int order) {
        TenantLoginChannel c = new TenantLoginChannel();
        c.setChannel(code);
        c.setSortOrder(order);
        c.setEnabled(true);
        return c;
    }

    @Test
    @DisplayName("getEnabledChannels(null) returns distinct channels across all tenants")
    void getEnabledChannelsNullTenantId() {
        when(channelMapper.selectList(any())).thenReturn(List.of(ch("email_password", 0), ch("sms", 1), ch("email_password", 2)));
        List<String> out = service.getEnabledChannels(null);
        assertEquals(2, out.size());
        assertTrue(out.contains("email_password"));
        assertTrue(out.contains("sms"));
    }

    @Test
    @DisplayName("getEnabledChannels(null) falls back to email_password when none enabled")
    void getEnabledChannelsNullEmpty() {
        when(channelMapper.selectList(any())).thenReturn(List.of());
        assertEquals(List.of("email_password"), service.getEnabledChannels(null));
    }

    @Test
    @DisplayName("getEnabledChannels(tenantId) returns channels for that tenant")
    void getEnabledChannelsForTenant() {
        when(channelMapper.selectList(any())).thenReturn(List.of(ch("email_password", 0), ch("sms", 1)));
        assertEquals(List.of("email_password", "sms"), service.getEnabledChannels(1L));
    }

    @Test
    @DisplayName("getEnabledChannels(tenantId) returns email_password when none configured")
    void getEnabledChannelsTenantEmpty() {
        when(channelMapper.selectList(any())).thenReturn(List.of());
        assertEquals(List.of("email_password"), service.getEnabledChannels(1L));
    }

    @Test
    @DisplayName("listChannels delegates to mapper")
    void listChannelsDelegates() {
        when(channelMapper.selectList(any())).thenReturn(List.of(ch("sms", 0)));
        assertEquals(1, service.listChannels(1L).size());
    }

    @Test
    @DisplayName("updateChannels updates existing record fields")
    void updateChannelsUpdateExisting() {
        TenantLoginChannel existing = ch("sms", 5);
        when(channelMapper.selectOne(any(QueryWrapper.class))).thenReturn(existing);

        ChannelUpdateRequest req = new ChannelUpdateRequest();
        req.setChannel("sms");
        req.setEnabled(false);
        req.setSortOrder(9);

        service.updateChannels(1L, List.of(req));
        assertEquals(false, existing.getEnabled());
        assertEquals(9, existing.getSortOrder());
        verify(channelMapper).updateById(existing);
        verify(channelMapper, never()).insert(any(TenantLoginChannel.class));
    }

    @Test
    @DisplayName("updateChannels inserts new record when missing, with defaults")
    void updateChannelsInsertNewWithDefaults() {
        when(channelMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        ChannelUpdateRequest req = new ChannelUpdateRequest();
        req.setChannel("sms");
        // null enabled / null sortOrder => defaults

        service.updateChannels(1L, List.of(req));
        ArgumentCaptor<TenantLoginChannel> cap = ArgumentCaptor.forClass(TenantLoginChannel.class);
        verify(channelMapper).insert(cap.capture());
        TenantLoginChannel inserted = cap.getValue();
        assertEquals("sms", inserted.getChannel());
        assertEquals(false, inserted.getEnabled());
        assertEquals(99, inserted.getSortOrder());
    }

    @Test
    @DisplayName("initDefaultChannels skips when records already exist")
    void initDefaultChannelsSkipExisting() {
        when(channelMapper.selectCount(any())).thenReturn(3L);
        service.initDefaultChannels(1L);
        verify(channelMapper, never()).insert(any(TenantLoginChannel.class));
    }

    @Test
    @DisplayName("initDefaultChannels inserts 3 defaults with email_password enabled")
    void initDefaultChannelsInsertsDefaults() {
        when(channelMapper.selectCount(any())).thenReturn(0L);
        service.initDefaultChannels(1L);
        ArgumentCaptor<TenantLoginChannel> cap = ArgumentCaptor.forClass(TenantLoginChannel.class);
        verify(channelMapper, times(3)).insert(cap.capture());
        List<TenantLoginChannel> rows = cap.getAllValues();
        // Only email_password is enabled
        long enabled = rows.stream().filter(c -> Boolean.TRUE.equals(c.getEnabled())).count();
        assertEquals(1, enabled);
        assertEquals("email_password", rows.stream().filter(c -> Boolean.TRUE.equals(c.getEnabled())).findFirst().get().getChannel());
    }
}
