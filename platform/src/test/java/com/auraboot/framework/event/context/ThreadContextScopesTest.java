package com.auraboot.framework.event.context;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ThreadContextScopesTest {
    private final ThreadLocal<String> value = new ThreadLocal<>();
    private final ThreadContextContributor contributor = new ThreadContextContributor() {
        public Snapshot capture() {
            String captured = value.get();
            return () -> set(captured);
        }
        public Snapshot empty() { return () -> value.remove(); }
    };

    private void set(String captured) {
        if (captured == null) value.remove(); else value.set(captured);
    }

    @Test
    void inheritedContextRestoresExistingWorkerAfterFailure() {
        var scopes = new ThreadContextScopes(List.of(contributor));
        value.set("submitted");
        var snapshot = scopes.capture();
        value.set("worker");
        assertThatThrownBy(() -> scopes.runWith(snapshot, () -> {
            assertThat(value.get()).isEqualTo("submitted");
            throw new IllegalStateException("task failure");
        })).isInstanceOf(IllegalStateException.class).hasMessage("task failure");
        assertThat(value.get()).isEqualTo("worker");
        value.remove();
    }

    @Test
    void newEventCannotInheritWorkerAuthorityAndNestedScopesRestore() {
        var scopes = new ThreadContextScopes(List.of(contributor));
        value.set("worker");
        scopes.runCleared(() -> {
            assertThat(value.get()).isNull();
            value.set("event");
            scopes.runCleared(() -> assertThat(value.get()).isNull());
            assertThat(value.get()).isEqualTo("event");
        });
        assertThat(value.get()).isEqualTo("worker");
        value.remove();
    }

    @Test
    void coreScopeNeedsNoDomainContributor() {
        var scopes = new ThreadContextScopes(List.of());
        scopes.runCleared(() -> assertThat(scopes.capture()).isEmpty());
    }
}
