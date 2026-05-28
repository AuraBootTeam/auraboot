package com.auraboot.framework.im.service.impl;

import com.auraboot.framework.im.dto.Announcement;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class ImConversationServiceImplAnnouncementParseTest {

    private Announcement invoke(String metadataJson) throws Exception {
        ImConversationServiceImpl impl = new ImConversationServiceImpl(null, null, null, null, null);
        return impl.parseAnnouncement(metadataJson);
    }

    @Test
    void nullMetadataReturnsNull() throws Exception {
        assertThat(invoke(null)).isNull();
    }

    @Test
    void blankMetadataReturnsNull() throws Exception {
        assertThat(invoke("")).isNull();
    }

    @Test
    void metadataWithoutAnnouncementReturnsNull() throws Exception {
        assertThat(invoke("{\"foo\":\"bar\"}")).isNull();
    }

    @Test
    void announcementNullSubkeyReturnsNull() throws Exception {
        assertThat(invoke("{\"announcement\":null}")).isNull();
    }

    @Test
    void wellFormedAnnouncementParses() throws Exception {
        String json = "{\"announcement\":{\"content\":\"Welcome\",\"updatedBy\":11,\"updatedByName\":\"alice\",\"updatedAt\":\"2026-05-29T01:00:00Z\"}}";
        Announcement a = invoke(json);
        assertThat(a).isNotNull();
        assertThat(a.content()).isEqualTo("Welcome");
        assertThat(a.updatedBy()).isEqualTo(11L);
        assertThat(a.updatedByName()).isEqualTo("alice");
        assertThat(a.updatedAt()).isEqualTo(java.time.Instant.parse("2026-05-29T01:00:00Z"));
    }

    @Test
    void malformedJsonReturnsNull() throws Exception {
        assertThat(invoke("{ this is not json")).isNull();
    }
}
