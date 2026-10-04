package com.auraboot.framework.plugin.extension;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class DecisionUsageContractTest {
    @Test
    void sourceNormalizesOnlyTypeAndKeepsPublicIdentifiersExact() {
        var source = new DecisionUsageAccessor.Source(" workflow ", " code ", " v1 ", " pid ");
        assertThat(source).isEqualTo(new DecisionUsageAccessor.Source("WORKFLOW", " code ", " v1 ", " pid "));
        assertThat(new DecisionUsageAccessor.Source(null, null, null, null))
                .isEqualTo(new DecisionUsageAccessor.Source("", "", "", ""));
    }

    @Test
    void sourceTypeIsIndependentOfHostLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(new DecisionUsageAccessor.Source(" integration ", "code", "v1", "pid").type())
                    .isEqualTo("INTEGRATION");
        } finally { Locale.setDefault(original); }
    }

    @Test
    void fragmentSnapshotsMetadataAndNormalizesOptionalContext() {
        Map<String, Object> metadata = new HashMap<>(Map.of("owner", "plugin"));
        Object content = new Object();
        var fragment = new DecisionUsageAccessor.Fragment(content, "binding", "path", metadata);
        metadata.clear();
        assertThat(fragment.content()).isSameAs(content);
        assertThat(fragment.binding()).isEqualTo("binding");
        assertThat(fragment.targetPath()).isEqualTo("path");
        assertThat(fragment.metadata()).containsEntry("owner", "plugin");
        assertThatThrownBy(() -> fragment.metadata().clear()).isInstanceOf(UnsupportedOperationException.class);
        var empty = new DecisionUsageAccessor.Fragment(null, null, null, null);
        assertThat(empty.binding()).isEmpty();
        assertThat(empty.targetPath()).isEmpty();
        assertThat(empty.metadata()).isEmpty();
    }
}
