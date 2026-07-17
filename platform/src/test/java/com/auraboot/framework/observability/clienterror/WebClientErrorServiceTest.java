package com.auraboot.framework.observability.clienterror;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.observability.clienterror.mapper.WebClientErrorMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebClientErrorServiceTest {

    @Mock
    private WebClientErrorMapper webClientErrorMapper;

    @InjectMocks
    private WebClientErrorService service;

    @BeforeEach
    void setUp() {
        MetaContext.setSystemTenantContext(9L);
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    @DisplayName("record stamps tenant, normalizes error type, clips oversize stack")
    void recordStampsAndClips() {
        WebClientErrorRequest req = new WebClientErrorRequest();
        req.setErrorType("weird-type");            // not 'unhandledrejection' -> normalized to 'error'
        req.setMessage("TypeError: x is not a function");
        req.setStack("a".repeat(20000));           // oversize -> clipped
        req.setPageUrl("/ops/errors");

        service.record(req);

        ArgumentCaptor<WebClientError> captor = ArgumentCaptor.forClass(WebClientError.class);
        org.mockito.Mockito.verify(webClientErrorMapper).insert(captor.capture());
        WebClientError saved = captor.getValue();
        assertThat(saved.getTenantId()).isEqualTo(9L);
        assertThat(saved.getErrorType()).isEqualTo("error");
        assertThat(saved.getStack()).hasSize(8000);
        assertThat(saved.getMessage()).isEqualTo("TypeError: x is not a function");
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("record keeps the unhandledrejection type")
    void recordKeepsRejectionType() {
        WebClientErrorRequest req = new WebClientErrorRequest();
        req.setErrorType("unhandledrejection");
        service.record(req);

        ArgumentCaptor<WebClientError> captor = ArgumentCaptor.forClass(WebClientError.class);
        org.mockito.Mockito.verify(webClientErrorMapper).insert(captor.capture());
        assertThat(captor.getValue().getErrorType()).isEqualTo("unhandledrejection");
    }

    @Test
    @DisplayName("page is tenant-scoped and returns pagination metadata")
    void pageTenantScoped() {
        when(webClientErrorMapper.pageByTenant(9L, 20, 0)).thenReturn(List.of(new WebClientError()));
        when(webClientErrorMapper.countByTenant(9L)).thenReturn(1L);

        PaginationResult<WebClientError> result = service.page(1, 20);

        assertThat(result.getRecords()).hasSize(1);
        assertThat(result.getTotal()).isEqualTo(1L);
    }
}
