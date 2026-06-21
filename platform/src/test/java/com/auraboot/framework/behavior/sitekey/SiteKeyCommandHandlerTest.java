package com.auraboot.framework.behavior.sitekey;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension.CommandContext;
import com.auraboot.framework.plugin.extension.DataAccessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;

import static com.auraboot.framework.plugin.extension.CommandHandlerExtension.DATA_ACCESSOR_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SiteKeyCommandHandlerTest {

    private SiteKeyRegistry registry;
    private DataAccessor dataAccessor;
    private SiteKeyCommandHandler handler;

    @BeforeEach
    void setUp() {
        registry = mock(SiteKeyRegistry.class);
        dataAccessor = mock(DataAccessor.class);
        handler = new SiteKeyCommandHandler(registry);
    }

    private CommandContext ctx(String commandType, String recordId, Map<String, Object> payload) {
        return CommandContext.builder()
                .tenantId(1L)
                .commandType(commandType)
                .modelCode(SiteKeyCommandHandler.MODEL)
                .recordId(recordId)
                .payload(payload)
                .settings(Map.of(DATA_ACCESSOR_KEY, dataAccessor))
                .build();
    }

    @Test
    @DisplayName("supports both create and disable command codes")
    void supportsBothCommands() {
        assertThat(handler.supports("behavior_site_key:create")).isTrue();
        assertThat(handler.supports("behavior_site_key:disable")).isTrue();
        assertThat(handler.supports("other:thing")).isFalse();
        assertThat(handler.getSupportedCommandTypes())
                .containsExactlyInAnyOrder("behavior_site_key:create", "behavior_site_key:disable");
    }

    @Test
    @DisplayName("handler owns persistence — requiresDslPersistence is false")
    void ownsPersistence() {
        assertThat(handler.requiresDslPersistence("behavior_site_key:create", Map.of(), null)).isFalse();
    }

    @Test
    @DisplayName("create generates a server-side abk_ key and inserts active row")
    void createGeneratesKey() {
        when(registry.existsAnyTenant(anyString())).thenReturn(false);
        when(dataAccessor.create(eq(SiteKeyCommandHandler.MODEL), org.mockito.ArgumentMatchers.anyMap()))
                .thenAnswer(inv -> inv.getArgument(1));

        Map<String, Object> payload = new HashMap<>();
        payload.put("name", "  My Landing Page  ");

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) handler.execute(
                ctx("behavior_site_key:create", null, payload));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(dataAccessor).create(eq(SiteKeyCommandHandler.MODEL), captor.capture());
        Map<String, Object> inserted = captor.getValue();

        assertThat(inserted.get("name")).isEqualTo("My Landing Page");
        assertThat(inserted.get("status")).isEqualTo("active");
        assertThat((String) inserted.get("site_key")).startsWith("abk_");
        assertThat(result.get("site_key")).isEqualTo(inserted.get("site_key"));
    }

    @Test
    @DisplayName("create rejects blank name with a field-level error (no fallback)")
    void createRejectsBlankName() {
        assertThatThrownBy(() -> handler.execute(ctx("behavior_site_key:create", null, Map.of("name", "  "))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("name is required");
        assertThatThrownBy(() -> handler.execute(ctx("behavior_site_key:create", null, Map.of())))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("create retries on collision then succeeds")
    void createRetriesOnCollision() {
        when(registry.existsAnyTenant(anyString())).thenReturn(true, false);
        when(dataAccessor.create(eq(SiteKeyCommandHandler.MODEL), org.mockito.ArgumentMatchers.anyMap()))
                .thenAnswer(inv -> inv.getArgument(1));

        handler.execute(ctx("behavior_site_key:create", null, Map.of("name", "App")));

        verify(registry, times(2)).existsAnyTenant(anyString());
        verify(dataAccessor, times(1)).create(eq(SiteKeyCommandHandler.MODEL), org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    @DisplayName("create fails loudly if no unique key after bounded retries")
    void createFailsAfterRetries() {
        when(registry.existsAnyTenant(anyString())).thenReturn(true);

        assertThatThrownBy(() -> handler.execute(ctx("behavior_site_key:create", null, Map.of("name", "App"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("unique site key");
    }

    @Test
    @DisplayName("disable flips status and evicts the resolver cache")
    void disableEvicts() {
        Map<String, Object> existing = Map.of("site_key", "abk_existing", "status", "active");
        when(dataAccessor.getById(SiteKeyCommandHandler.MODEL, "pid-1")).thenReturn(existing);
        when(dataAccessor.update(eq(SiteKeyCommandHandler.MODEL), eq("pid-1"), org.mockito.ArgumentMatchers.anyMap()))
                .thenAnswer(inv -> inv.getArgument(2));

        handler.execute(ctx("behavior_site_key:disable", "pid-1", Map.of()));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(dataAccessor).update(eq(SiteKeyCommandHandler.MODEL), eq("pid-1"), captor.capture());
        assertThat(captor.getValue().get("status")).isEqualTo("disabled");
        verify(registry).evict("abk_existing");
    }

    @Test
    @DisplayName("disable requires a record id")
    void disableRequiresRecordId() {
        assertThatThrownBy(() -> handler.execute(ctx("behavior_site_key:disable", null, Map.of())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("record id");
    }

    @Test
    @DisplayName("disable on a missing record fails with not found")
    void disableMissingRecord() {
        when(dataAccessor.getById(SiteKeyCommandHandler.MODEL, "ghost")).thenReturn(null);
        assertThatThrownBy(() -> handler.execute(ctx("behavior_site_key:disable", "ghost", Map.of())))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not found");
    }
}
