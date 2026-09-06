package com.auraboot.framework.meta.exception;

import com.auraboot.framework.common.dto.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R7 closure: a stale optimistic-version mutation must render as HTTP 409
 * with the wire-stable {@code 40900} code (mobile offline replay branches on
 * either), not as a generic 400 business error.
 */
class RecordVersionConflictRenderTest {

    private final MetaApiExceptionHandler handler = new MetaApiExceptionHandler();

    @Test
    @DisplayName("version conflict renders HTTP 409 + code 40900")
    void rendersConflictStatusAndWireCode() {
        RecordVersionConflictException e =
                new RecordVersionConflictException("Update failed: version conflict (expected version 7)");

        ResponseEntity<ApiResponse<Void>> response = handler.handleRecordVersionConflictException(e);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("40900");
        assertThat(response.getBody().getMessage()).contains("conflict");
    }

    @Test
    @DisplayName("exception stays a MetaServiceException for existing characterizations")
    void remainsAMetaServiceException() {
        RecordVersionConflictException e = new RecordVersionConflictException("version conflict (expected 1)");
        assertThat(e).isInstanceOf(MetaServiceException.class);
        assertThat(RecordVersionConflictException.status()).isEqualTo(HttpStatus.CONFLICT);
    }
}
