package com.auraboot.framework.application.web.handler;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.ValidationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataAccessResourceFailureException;
import com.auraboot.framework.meta.controller.CommandPipelineController;
import com.auraboot.framework.meta.service.impl.CommandPhaseRegistry;
import com.auraboot.framework.meta.exception.MetaApiExceptionHandler;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.MultipartException;
import com.auraboot.framework.i18n.service.I18nService;
import com.auraboot.framework.i18n.util.I18nLocaleResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * GlobalExceptionHandler localizes {@code $i18n:<key>} BusinessException messages to the request
 * locale via the existing i18n catalog (deep-review R1, exception-message i18n). Messages without
 * the prefix pass through untouched, so converting one message at a time is safe.
 */
class GlobalExceptionHandlerI18nTest {

    private GlobalExceptionHandler handler;
    private I18nService i18nService;
    private I18nLocaleResolver localeResolver;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
        i18nService = mock(I18nService.class);
        localeResolver = mock(I18nLocaleResolver.class);
        request = mock(HttpServletRequest.class);
        ReflectionTestUtils.setField(handler, "i18nService", i18nService);
        ReflectionTestUtils.setField(handler, "i18nLocaleResolver", localeResolver);
    }

    @Test
    void resolvesI18nPrefixedMessageToRequestLocale() {
        when(localeResolver.resolveLocale(request)).thenReturn("en-US");
        when(i18nService.getValue("en-US", "tenant.member.already_member"))
                .thenReturn("User is already a member of this tenant");

        String out = handler.localizeI18nMessage("$i18n:tenant.member.already_member", request);

        assertThat(out).isEqualTo("User is already a member of this tenant");
    }

    @Test
    void passesThroughPlainMessageUnchangedAndDoesNotTouchI18n() {
        // Not-yet-migrated message (e.g. interpolated) must be a no-op — zero behavior change.
        String out = handler.localizeI18nMessage("成员不存在: 42", request);

        assertThat(out).isEqualTo("成员不存在: 42");
        verifyNoInteractions(i18nService, localeResolver);
    }

    @Test
    void fallsBackToBaseLocaleWhenRequestLocaleMissingKey() {
        when(localeResolver.resolveLocale(request)).thenReturn("ja-JP");
        when(i18nService.getValue("ja-JP", "tenant.member.not_in_tenant")).thenReturn(null);
        when(i18nService.getValue("zh-CN", "tenant.member.not_in_tenant")).thenReturn("用户未加入任何租户");

        String out = handler.localizeI18nMessage("$i18n:tenant.member.not_in_tenant", request);

        assertThat(out).isEqualTo("用户未加入任何租户");
    }

    @Test
    void returnsBareKeyWhenWhollyUnresolved() {
        when(localeResolver.resolveLocale(request)).thenReturn("ja-JP");
        when(i18nService.getValue(anyString(), eq("tenant.member.unknown"))).thenReturn(null);

        String out = handler.localizeI18nMessage("$i18n:tenant.member.unknown", request);

        assertThat(out).isEqualTo("tenant.member.unknown");
    }

    @Test
    void nullMessagePassesThrough() {
        assertThat(handler.localizeI18nMessage(null, request)).isNull();
    }

    @Test
    void duplicateBusinessFailureDoesNotExposePersistenceCauseInDevResponse() {
        ReflectionTestUtils.setField(handler, "activeProfile", "dev");
        when(localeResolver.resolveLocale(request)).thenReturn("zh-CN");
        when(i18nService.getValue("zh-CN", "meta_record.duplicate"))
                .thenReturn("记录已存在，请检查唯一字段后再保存");
        DuplicateKeyException cause = new DuplicateKeyException("INSERT INTO private_table: duplicate key");
        BusinessException failure = new BusinessException(
                ResponseCode.BadParam, "$i18n:meta_record.duplicate", cause);

        var response = handler.handleBusinessException(failure, request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContext()).isInstanceOfSatisfying(Map.class, context -> {
            assertThat(context.get("detail")).isEqualTo("记录已存在，请检查唯一字段后再保存");
            assertThat(context).doesNotContainKey("cause");
            assertThat(context.toString()).doesNotContain("private_table", "INSERT INTO", "DuplicateKeyException");
        });
        assertThat(failure.getCause()).isSameAs(cause);
    }

    @Test
    void metaAdviceDoesNotOverrideBusinessFailureWithItsPersistenceCause() throws Exception {
        ReflectionTestUtils.setField(handler, "activeProfile", "dev");
        when(localeResolver.resolveLocale(any())).thenReturn("zh-CN");
        when(i18nService.getValue("zh-CN", "meta_record.duplicate"))
                .thenReturn("记录已存在，请检查唯一字段后再保存");
        CommandPhaseRegistry registry = mock(CommandPhaseRegistry.class);
        BusinessException businessFailure = new BusinessException(ResponseCode.BadParam,
                "$i18n:meta_record.duplicate", new DuplicateKeyException("INSERT INTO private_table"));
        when(registry.getAllPhases()).thenThrow(businessFailure)
                .thenThrow(new DataAccessResourceFailureException("Database private_connection unavailable"));
        var mvc = MockMvcBuilders.standaloneSetup(new CommandPipelineController(registry))
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .setControllerAdvice(new MetaApiExceptionHandler(), handler).build();

        var rejected = mvc.perform(get("/api/meta/command-phases"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.context.detail").value("记录已存在，请检查唯一字段后再保存"))
                .andReturn().getResponse().getContentAsString();
        assertThat(rejected).doesNotContain("INSERT INTO", "private_table", "DuplicateKeyException");

        var unavailable = mvc.perform(get("/api/meta/command-phases"))
                .andExpect(status().isInternalServerError())
                .andReturn().getResponse().getContentAsString();
        assertThat(unavailable).doesNotContain("private_connection", "meta_record.duplicate");
    }

    @Test
    void resolvesParameterizedBusinessException() {
        // BusinessException.i18n(key, args) carries the {0} args to the boundary.
        when(localeResolver.resolveLocale(request)).thenReturn("en-US");
        when(i18nService.getMessage("en-US", "tenant.member.not_found", 42L))
                .thenReturn("Member not found: 42");

        BusinessException ex = BusinessException.i18n("tenant.member.not_found", 42L);
        String out = handler.localizeBusinessMessage(ex, request);

        assertThat(out).isEqualTo("Member not found: 42");
    }

    @Test
    void parameterizedFallsBackToBaseLocale() {
        when(localeResolver.resolveLocale(request)).thenReturn("ja-JP");
        when(i18nService.getMessage("ja-JP", "tenant.not_found", 7L)).thenReturn(null);
        when(i18nService.getMessage("zh-CN", "tenant.not_found", 7L)).thenReturn("租户不存在: 7");

        BusinessException ex = BusinessException.i18n("tenant.not_found", 7L);

        assertThat(handler.localizeBusinessMessage(ex, request)).isEqualTo("租户不存在: 7");
    }

    @Test
    void staticBusinessExceptionStillResolvesWithoutArgs() {
        when(localeResolver.resolveLocale(request)).thenReturn("en-US");
        when(i18nService.getValue("en-US", "tenant.member.already_member"))
                .thenReturn("User is already a member of this tenant");

        BusinessException ex = new BusinessException("$i18n:tenant.member.already_member");

        assertThat(handler.localizeBusinessMessage(ex, request))
                .isEqualTo("User is already a member of this tenant");
    }

    @Test
    @SuppressWarnings("unchecked")
    void devEnvironmentDetailCarriesLocalizedTextNotTheRawKey() {
        // The frontend toasts context.detail. On a dev stack that field used to carry the raw
        // exception message, so rule reason keys leaked into the UI (workflow-demo submit showed
        // "annual_leave_insufficient"). Dev must show the same localized text as production.
        ReflectionTestUtils.setField(handler, "activeProfile", "dev");
        when(localeResolver.resolveLocale(request)).thenReturn("zh-CN");
        when(i18nService.getValue("zh-CN", "error.wd_leave_validation.annual_balance_not_found"))
                .thenReturn("未找到该员工的年假余额记录");

        BusinessException ex =
                new BusinessException("$i18n:error.wd_leave_validation.annual_balance_not_found");

        var response = handler.handleBusinessException(ex, request);

        Map<String, String> context =
                (Map<String, String>) response.getBody().getContext();
        assertThat(context).containsEntry("detail", "未找到该员工的年假余额记录");
        assertThat(context)
                .containsEntry("messageKey", "$i18n:error.wd_leave_validation.annual_balance_not_found");
        assertThat(context).containsEntry("exception", "BusinessException");
    }

    @Test
    void productionDetailIsTheLocalizedStringOnly() {
        ReflectionTestUtils.setField(handler, "activeProfile", "prod");
        when(localeResolver.resolveLocale(request)).thenReturn("zh-CN");
        when(i18nService.getValue("zh-CN", "error.wd_leave_validation.annual_leave_insufficient"))
                .thenReturn("剩余年假不足，无法提交该申请");

        BusinessException ex =
                new BusinessException("$i18n:error.wd_leave_validation.annual_leave_insufficient");

        var response = handler.handleBusinessException(ex, request);

        assertThat(response.getBody().getContext()).isEqualTo("剩余年假不足，无法提交该申请");
    }

    // ---- 35000 BadParam envelope localization (C1 bare-string fix) ----

    @Test
    void badParamEnvelopeResolvesToChineseForZhLocale() {
        when(localeResolver.resolveLocale(request)).thenReturn("zh-CN");
        when(i18nService.getValue("zh-CN", "common.error.badParam")).thenReturn("参数错误");
        MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
        when(ex.getBindingResult()).thenReturn(new BeanPropertyBindingResult(new Object(), "form"));

        var response = handler.handleValidationExceptions(ex, request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        // Same contract: code 35000, message field, structured context — only the copy is localized.
        assertThat(response.getBody().getCode()).isEqualTo("35000");
        assertThat(response.getBody().getMessage()).isEqualTo("参数错误");
        assertThat(response.getBody().getContext()).isInstanceOf(Map.class);
    }

    @Test
    void badParamEnvelopeResolvesToEnglishForEnLocale() {
        when(localeResolver.resolveLocale(request)).thenReturn("en-US");
        when(i18nService.getValue("en-US", "common.error.badParam")).thenReturn("Bad parameter");

        var response = handler.handleMultipartException(new MultipartException("not multipart"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("35000");
        assertThat(response.getBody().getMessage()).isEqualTo("Bad parameter");
    }

    @Test
    void badParamEnvelopeFallsBackToBaseLocaleThenLegacyDesc() {
        when(localeResolver.resolveLocale(request)).thenReturn("ja-JP");
        when(i18nService.getValue(anyString(), eq("common.error.badParam"))).thenReturn(null);

        var response = handler.handleMultipartException(new MultipartException("boom"), request);

        // Catalog gaps must never leak the raw key into the envelope — legacy desc is the floor.
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("Bad parameter");
    }

    @Test
    void businessValidationEnvelopeCarriesLocalizedBadParamMessage() {
        when(localeResolver.resolveLocale(request)).thenReturn("zh-CN");
        when(i18nService.getValue("zh-CN", "common.error.badParam")).thenReturn("参数错误");

        var response = handler.handleValidationException(
                new ValidationException(ResponseCode.CommonValidationFailed, "field is required"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("参数错误");
    }
}
