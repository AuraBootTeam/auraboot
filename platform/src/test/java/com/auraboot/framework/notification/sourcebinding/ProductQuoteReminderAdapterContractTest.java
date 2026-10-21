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
    @Test void actualReminderRuleFactoryMatchesBothApprovedTypedSourcePredicates() throws Exception {
        var source=ProductQuoteReminderNativeFacet.reminderSourceEntity();
        org.junit.jupiter.api.Assertions.assertEquals("qo_procurement_reminder_common",source.modelCode());
        var expected=RegisteredSourcePlanResolver.filters(source.equals());
        org.junit.jupiter.api.Assertions.assertEquals(java.util.Map.of("qo_pr_reminder_kind","quote","qo_pr_reminder_state","pending"),expected);
        var filters=new com.fasterxml.jackson.databind.ObjectMapper().readTree(ProductQuoteReminderNativeFacet.reminderRuleConditionFilter());
        org.junit.jupiter.api.Assertions.assertTrue(filters.isArray());
        var parsed=new java.util.LinkedHashMap<String,Object>();
        for(var predicate:filters) {
            org.junit.jupiter.api.Assertions.assertTrue(predicate.isObject());org.junit.jupiter.api.Assertions.assertEquals(3,predicate.size());
            org.junit.jupiter.api.Assertions.assertEquals("EQ",predicate.path("operator").textValue());
            org.junit.jupiter.api.Assertions.assertTrue(predicate.path("fieldName").isTextual());
            org.junit.jupiter.api.Assertions.assertTrue(predicate.path("value").isTextual());
            org.junit.jupiter.api.Assertions.assertNull(parsed.put(predicate.path("fieldName").textValue(),predicate.path("value").textValue()));
        }
        org.junit.jupiter.api.Assertions.assertEquals(expected,parsed,"Actual factory must retain kind and state, not a wider state-only rule");
    }

}
