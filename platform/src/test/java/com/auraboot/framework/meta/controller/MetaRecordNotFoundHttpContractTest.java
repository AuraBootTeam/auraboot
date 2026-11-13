package com.auraboot.framework.meta.controller;

import com.auraboot.framework.meta.exception.MetaApiExceptionHandler;
import com.auraboot.framework.meta.exception.MetaRecordNotFoundException;
import com.auraboot.framework.meta.exception.MetaServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Verifies Spring advice dispatch, including the generic business-error boundary. */
class MetaRecordNotFoundHttpContractTest {
    private final MockMvc mvc = buildMvc();

    private MockMvc buildMvc() {
        var global = new com.auraboot.framework.application.web.handler.GlobalExceptionHandler();
        var meta = new MetaApiExceptionHandler();
        org.springframework.test.util.ReflectionTestUtils.setField(meta, "globalExceptionHandler", global);
        return MockMvcBuilders.standaloneSetup(new ProbeController()).setControllerAdvice(meta, global)
                .setMessageConverters(new MappingJackson2HttpMessageConverter()).build();
    }

    @Test
    void absentRecordReturns404WithoutExposingItsInternalTarget() throws Exception {
        mvc.perform(get("/missing-record-contract"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("404"))
                .andExpect(jsonPath("$.message").value("Record not found"));
    }

    @Test
    void ordinaryBusinessErrorRemains400() throws Exception {
        mvc.perform(get("/business-error-contract"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void scopedDenialKeeps403BeforeItsNestedMissingRecordCause() throws Exception {
        mvc.perform(get("/denied-projection-contract"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("403"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(result -> org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("private_model", "foreign-record", "Record not found", "DataAccessorException"));
    }

    @Test
    void directDenialStillUsesTheExistingGlobalEnvelope() throws Exception {
        mvc.perform(get("/direct-denial-contract"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("403"));
    }

    // Non-static: this explicit standalone fixture is not a component-scan candidate.
    @RestController
    class ProbeController {
        @GetMapping("/missing-record-contract")
        String missing() { throw new MetaRecordNotFoundException("scheduled_task", "internal-fixture-pid"); }

        @GetMapping("/denied-projection-contract")
        String deniedProjection() {
            throw new org.springframework.security.access.AccessDeniedException("Plugin source data access denied",
                    new com.auraboot.framework.plugin.extension.DataAccessorException(
                            com.auraboot.framework.plugin.extension.DataAccessErrorCode.PERMISSION_DENIED,
                            new MetaRecordNotFoundException("private_model", "foreign-record")));
        }

        @GetMapping("/direct-denial-contract")
        String directDenial() { throw new org.springframework.security.access.AccessDeniedException("Access denied"); }

        @GetMapping("/business-error-contract")
        String invalid() { throw new MetaServiceException("Invalid business operation"); }
    }
}
