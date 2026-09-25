package com.auraboot.framework.application.release;

import com.auraboot.framework.application.tenant.MetaContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** MVC response contract only; authentication is covered by the real-stack suite. */
class PlatformReleaseControllerTest {
    @Test void disabledRegistrationReturnsUnavailableWithoutDatabaseFallback() throws Exception {
        var mapper = new ObjectMapper();
        var service = new PlatformReleaseRegistrationService(new MockEnvironment(), mapper);
        var mvc = standaloneSetup(new PlatformReleaseController(service, mapper)).build();
        try {
            MetaContext.setContext(1L, 2L, "test-user", "Test");
            mvc.perform(post("/api/admin/platform-releases/core").contentType("application/json").accept("application/json").content("{}"))
                    .andExpect(jsonPath("$.code").value("503"));
        } finally {
            MetaContext.clear();
            service.close();
        }
    }
}
