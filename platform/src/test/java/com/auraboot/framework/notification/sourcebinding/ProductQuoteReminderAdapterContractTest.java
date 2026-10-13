package com.auraboot.framework.notification.sourcebinding;

import com.auraboot.framework.plugin.extension.BackgroundDataAccessor;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Hermetic admission negatives. Actual savepoint semantics require the next real PG attempt. */
class ProductQuoteReminderAdapterContractTest {
    @Test void missingNativeLoaderTenantAndBackgroundCannotFallBackToRequestAccessor() throws Exception {
        try (var loader = new URLClassLoader(new URL[0], getClass().getClassLoader())) {
            var background = mock(BackgroundDataAccessor.class);
            assertThrows(IllegalArgumentException.class, () -> ProductQuoteReminderNativeFacet.actualSchedulerData(null, Path.of("missing.jar"), 1, background));
            assertThrows(IllegalArgumentException.class, () -> ProductQuoteReminderNativeFacet.actualSchedulerData(loader, null, 1, background));
            assertThrows(IllegalArgumentException.class, () -> ProductQuoteReminderNativeFacet.actualSchedulerData(loader, Path.of("missing.jar"), 0, background));
            assertThrows(IllegalArgumentException.class, () -> ProductQuoteReminderNativeFacet.actualSchedulerData(loader, Path.of("missing.jar"), 1, null));
        }
    }

    @Test void missingArtifactAndUnavailableRealTransactionCapabilityAreRejected() throws Exception {
        try (var loader = new URLClassLoader(new URL[0], getClass().getClassLoader())) {
            var background = mock(BackgroundDataAccessor.class);
            when(background.transactionResourceId()).thenReturn("");
            assertThrows(AssertionError.class, () -> ProductQuoteReminderNativeFacet.actualSchedulerData(loader, Path.of("missing.jar"), 1, background));
            when(background.transactionResourceId()).thenReturn("synthetic-pure-resource");
            assertThrows(ClassNotFoundException.class, () -> ProductQuoteReminderNativeFacet.actualSchedulerData(loader, Path.of("missing.jar"), 1, background));
        }
    }
}
