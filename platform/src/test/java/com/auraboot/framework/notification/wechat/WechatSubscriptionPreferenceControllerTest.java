package com.auraboot.framework.notification.wechat;

import com.auraboot.framework.notification.controller.WechatSubscriptionPreferenceController;
import com.auraboot.framework.permission.annotation.AuthenticatedAccess;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** DTO and MVC unit checks; standalone MVC does not prove deployed JWT authorization. */
class WechatSubscriptionPreferenceControllerTest {
    static final String PATH = "/api/notifications/wechat-subscription";
    @Test void catalogAndObservationReturnSeparateExplicitContracts() throws Exception {
        var service = mock(WechatSubscriptionPreferenceService.class);
        when(service.catalog()).thenReturn(new WechatSubscriptionPreferenceService.Catalog(List.of()));
        when(service.observe(any())).thenReturn(new WechatSubscriptionPreferenceService.Recorded(true, false));
        var mvc = MockMvcBuilders.standaloneSetup(new WechatSubscriptionPreferenceController(service))
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter()).build();
        mvc.perform(get(PATH + "/templates")).andExpect(status().isOk()).andExpect(jsonPath("$.data.records").isEmpty());
        mvc.perform(post(PATH + "/observations").contentType("application/json")
                .content("{\"results\":[{\"templateId\":\"synthetic\",\"status\":\"accept\"}],\"idempotencyKey\":\"request-1\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.recorded").value(true)).andExpect(jsonPath("$.data.delivered").value(false));
        verify(service).observe(any());
        assertThat(WechatSubscriptionPreferenceController.class.getAnnotation(AuthenticatedAccess.class)).isNotNull();
    }
    @Test void callerOwnershipAndUnknownFieldsAreRejectedEvenWhenJacksonIgnoresUnknownProperties() throws Exception {
        var service = mock(WechatSubscriptionPreferenceService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new WechatSubscriptionPreferenceController(service)).build();
        for (String field : List.of("tenantId", "userId", "openid", "appId", "delivered", "grantRef")) {
            mvc.perform(post(PATH + "/observations").contentType("application/json").content(
                    "{\"results\":[{\"templateId\":\"synthetic\",\"status\":\"accept\"}],\"idempotencyKey\":\"key\",\"" + field + "\":\"forged\"}"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post(PATH + "/observations").contentType("application/json").content(
                "{\"results\":[{\"templateId\":\"synthetic\",\"status\":\"accept\",\"openid\":\"forged\"}],\"idempotencyKey\":\"key\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void emptyUnknownStatusAndMalformedKeyAreRejectedBeforeService() throws Exception {
        var service = mock(WechatSubscriptionPreferenceService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new WechatSubscriptionPreferenceController(service)).build();
        for (String body : List.of("{}", "{\"results\":[],\"idempotencyKey\":\"key\"}",
                "{\"results\":[{\"templateId\":\"synthetic\",\"status\":\"unknown\"}],\"idempotencyKey\":\"key\"}",
                "{\"results\":[{\"templateId\":\"synthetic\",\"status\":\"accept\"}],\"idempotencyKey\":\"bad key\"}"))
            mvc.perform(post(PATH + "/observations").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
