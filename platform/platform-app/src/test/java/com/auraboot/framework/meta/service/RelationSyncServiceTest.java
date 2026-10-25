package com.auraboot.framework.meta.service;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.exception.MetaServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RelationSyncService unit test
 * Tests bidirectional relation synchronization capabilities
 *
 * @author AuraBoot Team
 * @since 3.0.0
 */
@DisplayName("RelationSyncService Test")
class RelationSyncServiceTest extends BaseIntegrationTest {

    @Autowired
    private RelationSyncService relationSyncService;

    @Test
    @DisplayName("Should return error when validating non-existent field")
    void shouldValidateBidirectionalConfig() {
        // Test with non-existent field - should return error
        List<String> errors = relationSyncService.validateBidirectionalConfig(
            "nonexistent_model", "nonexistent_field"
        );

        assertThat(errors).isNotEmpty();
        assertThat(errors.get(0)).contains("bidirectional configuration");
    }

    @Test
    @DisplayName("Should return empty map for model without inverse fields")
    void shouldGetInverseFieldsForModel() {
        // This test verifies the service can query inverse fields
        // Even if no bidirectional fields exist, it should return empty map
        Map<String, RelationSyncService.InverseFieldInfo> inverseFields =
            relationSyncService.getInverseFields("ab_tenant");

        assertThat(inverseFields).isNotNull();
    }

    @Test
    @DisplayName("Should return empty map when model does not exist")
    void shouldReturnEmptyMapForNonExistentModel() {
        // When getting inverse fields for a non-existent model
        Map<String, RelationSyncService.InverseFieldInfo> inverseFields =
            relationSyncService.getInverseFields("nonexistent_model_12345");

        // Then should return empty map, not null
        assertThat(inverseFields).isNotNull();
        assertThat(inverseFields).isEmpty();
    }

    @Test
    @DisplayName("Should throw exception when syncing with non-existent field")
    void shouldThrowExceptionForNonExistentField() {
        // When syncing with non-existent model/field, should throw MetaServiceException
        // because the field definition cannot be found
        assertThatThrownBy(() -> relationSyncService.syncInverseSide(
            "nonexistent_model",
            "test_record_id",
            "nonexistent_field",
            List.of("old_target"),
            List.of("new_target")
        )).isInstanceOf(MetaServiceException.class);
    }

    @Test
    @DisplayName("InverseFieldInfo record should store and retrieve values correctly")
    void shouldCreateInverseFieldInfoRecord() {
        // Given
        String targetModelCode = "target_model";
        String targetFieldCode = "target_field";
        String relationType = "one_to_many";
        boolean isOwningSide = true;

        // When
        RelationSyncService.InverseFieldInfo info = new RelationSyncService.InverseFieldInfo(
            targetModelCode,
            targetFieldCode,
            relationType,
            isOwningSide
        );

        // Then
        assertThat(info.targetModelCode()).isEqualTo(targetModelCode);
        assertThat(info.targetFieldCode()).isEqualTo(targetFieldCode);
        assertThat(info.relationType()).isEqualTo(relationType);
        assertThat(info.isOwningSide()).isTrue();
    }

    @Test
    @DisplayName("InverseFieldInfo should support all relation types")
    void shouldSupportAllRelationTypes() {
        // Test all supported relation types
        String[] relationTypes = {"one_to_one", "one_to_many", "many_to_one", "many_to_many"};

        for (String relationType : relationTypes) {
            RelationSyncService.InverseFieldInfo info = new RelationSyncService.InverseFieldInfo(
                "target_model",
                "target_field",
                relationType,
                true
            );

            assertThat(info.relationType()).isEqualTo(relationType);
        }
    }

    @Test
    @DisplayName("InverseFieldInfo should handle non-owning side")
    void shouldHandleNonOwningSide() {
        // Given - non-owning side configuration
        RelationSyncService.InverseFieldInfo info = new RelationSyncService.InverseFieldInfo(
            "target_model",
            "target_field",
            "one_to_many",
            false
        );

        // Then
        assertThat(info.isOwningSide()).isFalse();
    }
}
