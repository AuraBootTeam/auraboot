package com.auraboot.framework.branding;

import com.auraboot.framework.branding.mapper.AuthAppearanceStateMapper;

import com.auraboot.framework.application.tenant.MetaContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Initializes snapshot-based databases only through the explicit bootstrap entry. */
@Component
@RequiredArgsConstructor
public class AuthAppearanceSeeder {
    private final AuthAppearanceStateMapper mapper;

    public void seed() {
        MetaContext.runWithoutTenantFilter(() -> {
            if (mapper.selectById(1L) == null) {
                AuthAppearanceState state = new AuthAppearanceState();
                state.setId(1L);
                state.setVersion(0L);
                state.setPublishedVersion(0L);
                mapper.insert(state);
            }
        });
    }
}
