package com.auraboot.framework.meta.controller;

import com.auraboot.framework.meta.exception.MetaApiExceptionHandler;
import com.auraboot.framework.meta.exception.MetaRecordNotFoundException;
import com.auraboot.framework.meta.exception.MetaServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Verifies exception dispatch and the public response without a running database. */
class MetaRecordNotFoundHttpTest {
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new RecordController())
            .setControllerAdvice(new MetaApiExceptionHandler()).build();

    @Test
    void absentRecordReturnsSafeNotFound() throws Exception {
        mvc.perform(get("/record/absent").accept("application/json"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("404"))
                .andExpect(jsonPath("$.message").value("Resource not found"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.context").doesNotExist());
    }

    @Test
    void otherBusinessErrorsRemainBadRequests() throws Exception {
        mvc.perform(get("/record/invalid").accept("application/json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40000"));
    }

    @RestController
    static class RecordController {
        @GetMapping("/record/absent")
        String absent() {
            throw new MetaRecordNotFoundException("private_model", "private_record");
        }

        @GetMapping("/record/invalid")
        String invalid() {
            throw new MetaServiceException("Invalid field value");
        }
    }
}
