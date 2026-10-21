package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.exception.MetaServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class RecordCommandWriterGuardTest {
    @AfterEach void clearContext() { MetaContext.clear(); }
    private ModelDefinition model() {
        return ModelDefinition.builder().code("shared_bid").fields(List.of(FieldDefinition.builder()
            .code("managed").columnName("managed_flag").dataType("boolean").immutable(true).build()))
            .extension(Map.of("recordCommandWriters", Map.of("field", "managed", "commands",
                Map.of("create", List.of("app:dispatch"), "update", List.of("app:save"), "delete", List.of())))).build();
    }
    private void authorized(String command, Runnable action) {
        MetaContext.runWithCommandPermitPlan("ALL", null, "shared_bid", "bid-a",
            () -> MetaContext.runWithAuthorizedCommandCode(command, action));
    }
    @Test void genericCallerCannotCreateOrAcquireMarkedOwnership() {
        assertThatThrownBy(() -> RecordCommandWriterGuard.assertInputAllowed(model(), Map.of("managed", true), "create"))
            .isInstanceOf(MetaServiceException.class).hasMessageContaining("RECORD_WRITER_DENIED");
        assertThatThrownBy(() -> RecordCommandWriterGuard.assertInputAllowed(model(), Map.of("managed_flag", true), "update"))
            .hasMessageContaining("RECORD_WRITER_DENIED");
    }
    @Test void submittedFalseDoesNotOverridePersistedOwnership() {
        assertThatThrownBy(() -> FieldWriterGuard.assertUpdateAllowed(model(), Map.of("managed", false), Map.of("managed", true)))
            .hasMessageContaining("RECORD_WRITER_DENIED");
        assertThatThrownBy(() -> RecordCommandWriterGuard.assertStoredAllowed(model(), Map.of("managed", true), "delete"))
            .hasMessageContaining("RECORD_WRITER_DENIED");
    }
    @Test void exactCommandNeedsBothProvenanceAndPermitAndIsSpecificToOperation() {
        assertThatThrownBy(() -> MetaContext.runWithAuthorizedCommandCode("app:save",
            () -> RecordCommandWriterGuard.assertStoredAllowed(model(), Map.of("managed", true), "update")))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("published command permit plan");
        assertThatThrownBy(() -> MetaContext.runWithCommandPermitPlan("ALL", null, "shared_bid", "bid-a",
            () -> RecordCommandWriterGuard.assertStoredAllowed(model(), Map.of("managed", true), "update")))
            .hasMessageContaining("RECORD_WRITER_DENIED");
        assertThatThrownBy(() -> authorized("native:update",
            () -> RecordCommandWriterGuard.assertStoredAllowed(model(), Map.of("managed", true), "update")))
            .hasMessageContaining("RECORD_WRITER_DENIED");
        assertThatCode(() -> authorized("app:save",
            () -> RecordCommandWriterGuard.assertStoredAllowed(model(), Map.of("managed", true), "update"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> authorized("app:save",
            () -> RecordCommandWriterGuard.assertStoredAllowed(model(), Map.of("managed", true), "delete")))
            .hasMessageContaining("RECORD_WRITER_DENIED");
    }
    @Test void finalPredicateUsesPersistedPhysicalMarkerAndKeepsUnmarkedNullRows() {
        var sql=new StringBuilder("UPDATE shared_bid SET amount = ? WHERE tenant_id = ?");
        RecordCommandWriterGuard.appendStoredPredicate(sql,model(),"update");
        assertThat(sql.toString()).endsWith(" AND managed_flag IS DISTINCT FROM TRUE");
        authorized("app:save",()->{
            var allowed=new StringBuilder("WHERE tenant_id = ?");
            RecordCommandWriterGuard.appendStoredPredicate(allowed,model(),"update");
            assertThat(allowed.toString()).isEqualTo("WHERE tenant_id = ?");
        });
        assertThatCode(()->RecordCommandWriterGuard.assertStoredAllowed(model(),Map.of(),"update")).doesNotThrowAnyException();
        assertThatCode(()->RecordCommandWriterGuard.assertStoredAllowed(model(),Map.of("managed",false),"delete")).doesNotThrowAnyException();
    }
    @Test void malformedPolicyAndNonBooleanMarkersFailClosed() {
        var invalid=ModelDefinition.builder().code("bad").extension(Map.of("recordCommandWriters",true)).build();
        assertThatThrownBy(()->RecordCommandWriterGuard.appendStoredPredicate(new StringBuilder(),invalid,"update"))
            .hasMessageContaining("Invalid recordCommandWriters");
        assertThatThrownBy(()->RecordCommandWriterGuard.assertInputAllowed(model(),Map.of("managed","true"),"create"))
            .hasMessageContaining("boolean");
    }
    @Test void undeclaredModelsKeepExistingBehavior() {
        var ordinary=ModelDefinition.builder().code("ordinary").build();
        assertThatCode(()->RecordCommandWriterGuard.assertInputAllowed(ordinary,Map.of("managed",true),"create")).doesNotThrowAnyException();
        var sql=new StringBuilder("WHERE tenant_id = ?");RecordCommandWriterGuard.appendStoredPredicate(sql,ordinary,"delete");
        assertThat(sql.toString()).isEqualTo("WHERE tenant_id = ?");
    }
    @Test void exactWriterCannotClearOrAcquireOwnershipAfterCreation() {
        var clear=new java.util.HashMap<String,Object>();clear.put("managed_flag",null);
        for (Map<String,Object> proposed : List.of(Map.<String,Object>of("managed",false),clear))
            assertThatThrownBy(()->authorized("app:save",()->FieldWriterGuard.assertUpdateAllowed(model(),proposed,Map.of("managed",true))))
                .hasMessageContaining("RECORD_OWNERSHIP_IMMUTABLE");
        assertThatThrownBy(()->authorized("app:save",()->FieldWriterGuard.assertUpdateAllowed(model(),Map.of("managed",true),Map.of("managed",false))))
            .hasMessageContaining("RECORD_OWNERSHIP_IMMUTABLE");
        assertThatCode(()->authorized("app:save",()->FieldWriterGuard.assertUpdateAllowed(model(),Map.of("managed_flag",true),Map.of("managed",true))))
            .doesNotThrowAnyException();
        assertThatCode(()->FieldWriterGuard.assertUpdateAllowed(model(),Map.of("managed",false),Map.of("managed",false)))
            .doesNotThrowAnyException();
    }
    @Test void markerRequiresAnImmutablePhysicalDefinitionAndUnambiguousAliases() {
        var mutable=model();mutable.getFields().get(0).setImmutable(false);
        assertThatThrownBy(()->RecordCommandWriterGuard.validatePolicy(mutable)).hasMessageContaining("Invalid recordCommandWriters");
        assertThatThrownBy(()->RecordCommandWriterGuard.assertInputAllowed(model(),Map.of("managed",false,"managed_flag",true),"update"))
            .hasMessageContaining("Conflicting");
        assertThatThrownBy(()->authorized("app:save",()->RecordCommandWriterGuard.assertLegacyMarkerUntouched(model(),Map.of("managed_flag",false))))
            .hasMessageContaining("RECORD_OWNERSHIP_IMMUTABLE");
    }

}
