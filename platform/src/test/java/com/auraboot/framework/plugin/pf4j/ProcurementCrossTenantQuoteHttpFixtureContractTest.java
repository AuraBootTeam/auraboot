package com.auraboot.framework.plugin.pf4j;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ProcurementCrossTenantQuoteHttpFixtureContractTest {
 @Test void supplierHasExactlyProductionTwoCodesAndSelf() {
  var c=ProcurementCrossTenantQuoteHttpFixtureIT.SUPPLIER_CODES;
  ProcurementCrossTenantQuoteHttpFixtureIT.validateSupplierRole(c,"self");
  for(var bad:List.of(List.of("*"),List.of("meta.command.execute","qo.srm.use","qo.procurement.manage"),List.of("meta.command.execute","meta.command.execute")))
   assertThrows(AssertionError.class,()->ProcurementCrossTenantQuoteHttpFixtureIT.validateSupplierRole(bad,"self"));
  assertThrows(AssertionError.class,()->ProcurementCrossTenantQuoteHttpFixtureIT.validateSupplierRole(c,"all"));
 }
 @Test void onlyExactFiveInitializerModelsAndOnePreparationSelector() {
  assertEquals(5,ProcurementCrossTenantQuoteHttpFixtureIT.INITIAL_MODELS.size());
  for(String model:List.of("pr_supplier_common","pr_supplier_pcba_contact","s2p_bid","qo_procurement_submission_common"))
   assertThrows(IllegalArgumentException.class,()->ProcurementCrossTenantQuoteHttpFixtureIT.initCommand(model));
  assertEquals(1,Arrays.stream(ProcurementCrossTenantQuoteHttpFixtureIT.class.getDeclaredMethods()).filter(m->m.isAnnotationPresent(Test.class)).count());
  for(String model:ProcurementCrossTenantQuoteHttpFixtureIT.INITIAL_MODELS)assertTrue(ProcurementCrossTenantQuoteHttpFixtureIT.initCommand(model).length()<=64);
 }
}
