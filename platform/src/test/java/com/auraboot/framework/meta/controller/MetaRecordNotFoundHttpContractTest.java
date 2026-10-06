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
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
            .setControllerAdvice(new MetaApiExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter()).build();

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

    // Non-static: this explicit standalone fixture is not a component-scan candidate.
    @RestController
    class ProbeController {
        @GetMapping("/missing-record-contract")
        String missing() { throw new MetaRecordNotFoundException("scheduled_task", "internal-fixture-pid"); }

        @GetMapping("/business-error-contract")
        String invalid() { throw new MetaServiceException("Invalid business operation"); }
    }
}
