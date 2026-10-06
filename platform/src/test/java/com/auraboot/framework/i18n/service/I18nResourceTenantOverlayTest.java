package com.auraboot.framework.i18n.service;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.i18n.entity.I18nResource;
import com.auraboot.framework.i18n.mapper.I18nResourceMapper;
import com.auraboot.framework.i18n.service.impl.I18nResourceServiceImpl;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class I18nResourceTenantOverlayTest {
 private final I18nResourceMapper mapper = mock(I18nResourceMapper.class);
 private final I18nResourceServiceImpl service = new I18nResourceServiceImpl(mapper);
 @AfterEach void clearContext() { MetaContext.clear(); }
 private I18nResource entry(String key, String value) { return I18nResource.builder().i18nKey(key).value(value).build(); }
 private void systemResource() {
  // The public/system pack reads through the fixed-scope mapper statement: its SQL
  // predicate pins tenant_id=0 and the tenant line interceptor is ignored for that
  // statement only, so the service layer never toggles a MetaContext bypass.
  when(mapper.selectSystemByLang("zh-CN")).thenReturn(List.of(entry("action.more", "更多")));
 }
 @Test void authenticatedPackIncludesSystemWithoutBroadTenantBypass() {
  MetaContext.setContext(42L, 7L, "user", "fixture"); systemResource();
  when(mapper.selectAllByLang(42L, "zh-CN")).thenAnswer(invocation -> {
   assertThat(MetaContext.isTenantFilterBypassed()).isFalse(); return List.of(entry("plugin.label", "仓库"));
  });
  assertThat(service.getResourceMapByLang("zh-CN")).containsEntry("action.more", "更多").containsEntry("plugin.label", "仓库");
  assertThat(MetaContext.isTenantFilterBypassed()).isFalse();
  assertThat(MetaContext.getCurrentTenantId()).isEqualTo(42L);
  verify(mapper).selectAllByLang(42L, "zh-CN");
  verify(mapper).selectSystemByLang("zh-CN");
  verifyNoMoreInteractions(mapper);
 }
 @Test void publicPackLoadsDeclaredPublicResourcesWithoutTenantContext() {
  systemResource();
  assertThat(service.getResourceMapByLang("zh-CN")).containsEntry("action.more", "更多").doesNotContainKey("plugin.label");
  verify(mapper).selectSystemByLang("zh-CN");
  verifyNoMoreInteractions(mapper);
  assertThat(MetaContext.isTenantFilterBypassed()).isFalse();
 }
 @Test void tenantTranslationsOverrideSystemValues() {
  MetaContext.setContext(42L, 7L, "user", "fixture"); systemResource();
  when(mapper.selectAllByLang(42L, "zh-CN")).thenReturn(List.of(entry("action.more", "租户更多")));
  assertThat(service.getResourceMapByLang("zh-CN")).containsEntry("action.more", "租户更多");
 }
 @Test void failedSystemReadRestoresTenantFilterScope() {
  MetaContext.setContext(42L, 7L, "user", "fixture");
  when(mapper.selectAllByLang(42L, "zh-CN")).thenReturn(List.of());
  when(mapper.selectSystemByLang("zh-CN")).thenThrow(new IllegalStateException("fixture failure"));
  assertThatThrownBy(() -> service.getResourceMapByLang("zh-CN")).isInstanceOf(IllegalStateException.class);
  assertThat(MetaContext.isTenantFilterBypassed()).isFalse();
  assertThat(MetaContext.getCurrentTenantId()).isEqualTo(42L);
 }
}
