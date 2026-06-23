package com.auraboot.framework.behavior.keyed;

import com.auraboot.framework.auth.service.ApiRateLimiter;
import com.auraboot.framework.behavior.dto.BehaviorEventInput;
import com.auraboot.framework.behavior.sitekey.SiteKeyRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit test for {@link KeyedCollectGuard} — the ordered abuse-protection chain. Collaborators
 * are mocked; each branch (happy + every rejection) is asserted, plus short-circuit ordering.
 */
class KeyedCollectGuardTest {

    private final SiteKeyRegistry registry = mock(SiteKeyRegistry.class);
    private final SiteKeyOriginPolicy origin = mock(SiteKeyOriginPolicy.class);
    private final ApiRateLimiter rateLimiter = mock(ApiRateLimiter.class);
    private final KeyedCollectGuard guard =
            new KeyedCollectGuard(registry, origin, rateLimiter, 600, 300, 50);

    private List<BehaviorEventInput> oneEvent() {
        BehaviorEventInput in = new BehaviorEventInput();
        in.setEventId("e1");
        in.setEventName("page_view");
        return List.of(in);
    }

    @Test
    void happyPath_returnsResolvedTenant() {
        when(registry.resolveTenant("abk_x")).thenReturn(Optional.of(42L));
        when(origin.isOriginAllowed("abk_x", "https://a.com")).thenReturn(true);
        when(rateLimiter.isAllowed(anyString(), anyInt())).thenReturn(true);

        assertThat(guard.check("abk_x", "https://a.com", "1.2.3.4", oneEvent())).isEqualTo(42L);
    }

    @Test
    void unknownKey_403_siteKeyInvalid() {
        when(registry.resolveTenant("abk_x")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> guard.check("abk_x", "https://a.com", "1.2.3.4", oneEvent()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("site_key_invalid");
        verifyNoInteractions(origin, rateLimiter);
    }

    @Test
    void blankKey_403() {
        assertThatThrownBy(() -> guard.check("  ", "https://a.com", "1.2.3.4", oneEvent()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("site_key_invalid");
    }

    @Test
    void originNotAllowed_403() {
        when(registry.resolveTenant("abk_x")).thenReturn(Optional.of(42L));
        when(origin.isOriginAllowed("abk_x", "https://evil.com")).thenReturn(false);
        assertThatThrownBy(() -> guard.check("abk_x", "https://evil.com", "1.2.3.4", oneEvent()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("origin_not_allowed");
    }

    @Test
    void perKeyRateExceeded_429() {
        when(registry.resolveTenant("abk_x")).thenReturn(Optional.of(42L));
        when(origin.isOriginAllowed(anyString(), anyString())).thenReturn(true);
        when(rateLimiter.isAllowed("collect:key:abk_x", 600)).thenReturn(false);
        assertThatThrownBy(() -> guard.check("abk_x", "https://a.com", "1.2.3.4", oneEvent()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("rate_limited");
    }

    @Test
    void batchTooLarge_400() {
        when(registry.resolveTenant("abk_x")).thenReturn(Optional.of(42L));
        when(origin.isOriginAllowed(anyString(), anyString())).thenReturn(true);
        when(rateLimiter.isAllowed(anyString(), anyInt())).thenReturn(true);
        BehaviorEventInput in = new BehaviorEventInput();
        in.setEventId("e");
        in.setEventName("x");
        List<BehaviorEventInput> tooMany = Collections.nCopies(51, in);
        assertThatThrownBy(() -> guard.check("abk_x", "https://a.com", "1.2.3.4", tooMany))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("batch_too_large");
    }
}
