package com.auraboot.framework.plugin.extension;

import org.junit.jupiter.api.Test;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class HostCapabilityDefaultsTest {
    @Test
    void olderFileHostsCannotImplyMetadataRelationsRetentionOrPublishUnlinkedArtifacts() {
        FileAccessor accessor = new FileAccessor() {
            public InputStream open(String id) { throw new AssertionError("Default must not open bytes"); }
            public SavedFile save(String name, String type, byte[] bytes) { throw new AssertionError("Default must not save bytes"); }
        };
        assertThat(accessor.describe("file-pid")).isNull();
        assertThat(accessor.isLinkedTo("file-pid", "order", "record-pid", "attachment")).isFalse();
        assertThat(accessor.retain("file-pid")).isFalse();
        assertThatThrownBy(() -> accessor.saveAndLink("export.csv", "text/csv", new byte[]{1}, "order", "record-pid", "attachment"))
                .isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("relation capability");
        assertThatThrownBy(() -> accessor.listLinked("order", "record-pid", "attachment"))
                .isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("listing capability");
        assertThatThrownBy(() -> accessor.delete("file-pid", "actor-pid"))
                .isInstanceOf(UnsupportedOperationException.class).hasMessageContaining("deletion capability");
    }

    @Test
    void fileMetadataKeepsPublicIdentityAndDownloadDetails() {
        var saved = new FileAccessor.SavedFile("file-pid", "export.csv", 42L, "/files/file-pid");
        assertThat(saved.fileId()).isEqualTo("file-pid");
        assertThat(saved.originalName()).isEqualTo("export.csv");
        assertThat(saved.size()).isEqualTo(42L);
        assertThat(saved.url()).isEqualTo("/files/file-pid");
        var metadata = new FileAccessor.FileMetadata("file-pid", "export.csv", 42L, "text/csv", "actor-pid", "released");
        assertThat(metadata.fileId()).isEqualTo(saved.fileId());
        assertThat(metadata.originalName()).isEqualTo(saved.originalName());
        assertThat(metadata.size()).isEqualTo(saved.size());
        assertThat(metadata.contentType()).isEqualTo("text/csv");
        assertThat(metadata.ownerUserId()).isEqualTo("actor-pid");
        assertThat(metadata.status()).isEqualTo("released");
    }

    @Test
    void collaborationCompatibilityDefaultForwardsOnlyTheOriginalReadGrantArguments() {
        List<List<Object>> calls = new ArrayList<>();
        List<String> users = List.of("user-a", "user-b");
        RecordShareAccessor accessor = (tenant, resource, record, recipients) -> calls.add(List.of(tenant, resource, record, recipients));
        accessor.replaceReadUpdateSharesForUsers(7L, "order", "record-pid", users);
        assertThat(calls).containsExactly(List.of(7L, "order", "record-pid", users));
    }

    @Test
    void codedDataAccessFailurePreservesCauseAndRejectsMissingCode() {
        var cause = new IllegalStateException("host denied");
        var failure = new DataAccessorException(DataAccessErrorCode.PERMISSION_DENIED, cause);
        assertThat(failure.code()).isEqualTo(DataAccessErrorCode.PERMISSION_DENIED);
        assertThat(failure).hasCause(cause).hasMessage("Plugin data access failed: PERMISSION_DENIED");
        assertThatThrownBy(() -> new DataAccessorException(null, cause)).isInstanceOf(NullPointerException.class).hasMessage("code");
    }
}
