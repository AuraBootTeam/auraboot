package com.auraboot.framework.plugin.pf4j;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProcurementControlledOrderNativeContractTest {
    private static final String COOKIE="order-master=authenticated";
    private ObjectNode login(){return ProcurementQuoteLifecycleErp.JSON.createObjectNode().put("acctID","controlled-account").put("username","local-reader").put("appId","local-app").put("appSecret","local-test-secret").put("lcid",2052);}
    private ObjectNode query(){return ProcurementQuoteLifecycleErp.JSON.valueToTree(Map.of("data",Map.of("FormId","BD_Supplier","FieldKeys",ProcurementQuoteLifecycleErp.FIELDS,"FilterString","FSupplierId=701 OR FSupplierId=702","OrderString","FSupplierId ASC","StartRow",0,"Limit",100,"TopRowCount",0)));}
    @Test void exactTwoSupplierMasterProtocolPreservesFullConditionsAndNeverAcceptsSave() throws Exception {
        var server=new ProcurementControlledOrderMasterErp("controlled-account");assertEquals(1,server.reply(ProcurementQuoteLifecycleErp.LOGIN,login(),null).path("LoginResultType").intValue());
        assertEquals(2,server.reply(ProcurementQuoteLifecycleErp.QUERY,query(),COOKIE).size());
        for(String id:List.of("701","702")) {
            var response=server.reply(ProcurementQuoteLifecycleErp.VIEW,ProcurementQuoteLifecycleErp.JSON.valueToTree(Map.of("formid","BD_Supplier","data",Map.of("Id",id))),COOKIE);
            var value=response.path("Result").path("Result");assertEquals(id,value.path("Id").asText());assertEquals(2,value.path("SupplierFinance").size());assertEquals(Integer.parseInt(id)+200,value.path("SupplierContact").get(0).path("Id").intValue());assertEquals("701".equals(id)?"13900000071":"13900000072",value.path("SupplierContact").get(0).path("Mobile").asText());
        }
        var condition=server.reply(ProcurementQuoteLifecycleErp.VIEW,ProcurementQuoteLifecycleErp.JSON.valueToTree(Map.of("formid","BD_PaymentCondition","data",Map.of("Id","801"))),COOKIE).path("Result").path("Result");
        assertEquals(2,condition.path("PayRecCondtionEntry").size());assertEquals(0,new java.math.BigDecimal("59.99999999999999999999999999999").compareTo(condition.path("PayRecCondtionEntry").get(1).path("Rate").decimalValue()));
        assertThrows(IllegalArgumentException.class,()->server.reply(ProcurementQuoteLifecycleErp.ROOT+"DynamicFormService.Save.common.kdsvc",ProcurementQuoteLifecycleErp.JSON.createObjectNode(),COOKIE));
    }
    @Test void masterAuthenticationScopeFieldsAndFormsFailClosed() {
        var server=new ProcurementControlledOrderMasterErp("controlled-account");
        assertThrows(IllegalArgumentException.class,()->server.reply(ProcurementQuoteLifecycleErp.LOGIN,login().put("acctID","foreign"),null));
        assertThrows(IllegalArgumentException.class,()->server.reply(ProcurementQuoteLifecycleErp.QUERY,query(),null));
        var extra=query();((ObjectNode)extra.path("data")).put("unknown",true);assertThrows(IllegalArgumentException.class,()->server.reply(ProcurementQuoteLifecycleErp.QUERY,extra,COOKIE));
        var unknown=query();((ObjectNode)unknown.path("data")).put("FilterString","FSupplierId=703");assertThrows(IllegalArgumentException.class,()->server.reply(ProcurementQuoteLifecycleErp.QUERY,unknown,COOKIE));
        assertThrows(IllegalArgumentException.class,()->server.reply(ProcurementQuoteLifecycleErp.VIEW,ProcurementQuoteLifecycleErp.JSON.valueToTree(Map.of("formid","BD_MATERIAL","data",Map.of("Id","701"))),COOKIE));
    }
    @Test void addressAppendixImportsOnlyComposedQuoteOwnerNeverOtherTwentyTwoOriginalOwners() {
        var rows=new ArrayList<Map<String,Object>>();for(int i=0;i<23;i++)rows.add(Map.of("pluginId",i==6?"com.auraboot.quote-procurement":"other-"+i,"testDirectory","/synthetic/owner-"+i));
        assertEquals(java.nio.file.Path.of("/synthetic/owner-6"),ProcurementControlledOrderNativeIT.addressOwner(Map.of("ownerDirectories",rows)));
        rows.add(rows.get(6));assertThrows(AssertionError.class,()->ProcurementControlledOrderNativeIT.addressOwner(Map.of("ownerDirectories",rows)));
        rows.removeIf(v->"com.auraboot.quote-procurement".equals(v.get("pluginId")));assertThrows(AssertionError.class,()->ProcurementControlledOrderNativeIT.addressOwner(Map.of("ownerDirectories",rows)));
    }
    @Test void originalEightStagesAndResponseSemanticsRemainExact() {
        var stages=List.of("pending","failed","unknown","mixed","createSuccess","unknownCreation","concurrentCreation","partialCreation");
        ProcurementControlledOrderNativeIT.validateStageNames(stages);
        assertThrows(AssertionError.class,()->ProcurementControlledOrderNativeIT.validateStageNames(stages.subList(0,7)));
        var duplicate=new ArrayList<>(stages);duplicate.set(7,"pending");assertThrows(AssertionError.class,()->ProcurementControlledOrderNativeIT.validateStageNames(duplicate));
        assertEquals("unknown",ProcurementControlledOrderNativeIT.responseMode("unknownCreation",0));assertEquals("succeeded",ProcurementControlledOrderNativeIT.initialState("mixed",0));assertEquals("failed",ProcurementControlledOrderNativeIT.initialState("mixed",1));
        assertEquals("success",ProcurementControlledOrderNativeIT.responseMode("partialCreation",0));assertEquals("failed",ProcurementControlledOrderNativeIT.responseMode("partialCreation",1));
        assertThrows(IllegalArgumentException.class,()->ProcurementControlledOrderNativeIT.initialState("invented",0));
    }
}
