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

    @Test void storedJsonDecodesTheActualNestedFrozenPaymentTextNode() throws Exception {
        var json=ProcurementQuoteLifecycleErp.JSON;
        var terms=json.readTree("{\"kind\":\"KINGDEE_PAYMENT_CONDITION\",\"condition\":{\"Number\":\"000012\",\"PayRecCondtionEntry\":[{\"Rate\":40.00000000000000000000000000001},{\"Rate\":59.99999999999999999999999999999}]}}");
        var outer=json.createObjectNode().put("qo_pr_quote_payment_terms_snapshot",json.writeValueAsString(terms));
        var header=ProcurementControlledOrderNativeIT.storedJson(json.writeValueAsString(outer));
        assertTrue(header.path("qo_pr_quote_payment_terms_snapshot").isTextual(),"The real frozen header stores the snapshot as JSON text");
        var actual=ProcurementControlledOrderNativeIT.storedJson(header.get("qo_pr_quote_payment_terms_snapshot"));
        assertEquals("KINGDEE_PAYMENT_CONDITION",actual.path("kind").asText());
        assertEquals(2,actual.path("condition").path("PayRecCondtionEntry").size());
        assertEquals("000012",actual.path("condition").path("Number").textValue());
        assertEquals(0,new java.math.BigDecimal("59.99999999999999999999999999999").compareTo(actual.path("condition").path("PayRecCondtionEntry").get(1).path("Rate").decimalValue()));
    }
    @Test void storedJsonPreservesStringMapObjectNodeAndExactDecimalShapes() throws Exception {
        var json=ProcurementQuoteLifecycleErp.JSON;
        var facts=new LinkedHashMap<String,Object>();facts.put("kind","KINGDEE_PAYMENT_CONDITION");facts.put("amount",new java.math.BigDecimal("12345678901234567890.000000000001"));facts.put("code","000012");facts.put("flag",false);facts.put("embeddedText","{\"notRecursivelyParsed\":true}");
        var object=json.valueToTree(facts);
        for(Object raw:List.of(json.writeValueAsString(facts),facts,object)) {
            var actual=ProcurementControlledOrderNativeIT.storedJson(raw);
            assertTrue(actual.isObject());assertEquals("KINGDEE_PAYMENT_CONDITION",actual.path("kind").asText());
            assertEquals(0,new java.math.BigDecimal("12345678901234567890.000000000001").compareTo(actual.path("amount").decimalValue()));
            assertEquals("000012",actual.path("code").textValue());assertTrue(actual.path("flag").isBoolean());assertFalse(actual.path("flag").booleanValue());
            assertTrue(actual.path("embeddedText").isTextual());assertEquals("{\"notRecursivelyParsed\":true}",actual.path("embeddedText").textValue());
        }
    }
    @Test void storedJsonMalformedTextAndNullAreNotSilentlyTreatedAsObjects() {
        assertThrows(Exception.class,()->ProcurementControlledOrderNativeIT.storedJson(com.fasterxml.jackson.databind.node.TextNode.valueOf("{broken")));
        assertThrows(Exception.class,()->ProcurementControlledOrderNativeIT.storedJson("{broken"));
        assertThrows(AssertionError.class,()->ProcurementControlledOrderNativeIT.storedJson(null));
    }
    @Test void everySupplierActorIdentityFitsBothRealUserAndEmailColumnsWithoutCollisions() {
        var stages=List.of("pending","failed","unknown","mixed","createSuccess","unknownCreation","concurrentCreation","partialCreation");
        var seen=new java.util.HashSet<String>();
        for(String provider:List.of("ps_0123456789abcdef01234567","ps_fedcba9876543210fedcba98")) {
            for(String stage:stages) for(int index=0;index<2;index++) {
                String key=ProcurementControlledOrderNativeIT.supplierActorKey(provider,stage,index);
                assertTrue(key.length()<=64,"ab_user.user_name limit for "+stage);
                assertTrue((key+"@example.invalid").length()<=64,"ab_user.email limit for "+stage);
                assertTrue(seen.add(key),"Each provider, stage and supplier index must remain distinct");
                assertEquals(key,ProcurementControlledOrderNativeIT.supplierActorKey(provider,stage,index));
            }
        }
        assertEquals(32,seen.size());
    }

    @Test void supplierActorKeyRejectsUnknownStagesForeignPrefixesAndInvalidIndexes() {
        String provider="ps_0123456789abcdef01234567";
        assertThrows(AssertionError.class,()->ProcurementControlledOrderNativeIT.supplierActorKey(provider,"invented",0));
        assertThrows(AssertionError.class,()->ProcurementControlledOrderNativeIT.supplierActorKey("foreign","pending",0));
        for(int index:List.of(-1,2))assertThrows(AssertionError.class,()->ProcurementControlledOrderNativeIT.supplierActorKey(provider,"pending",index));
    }

}
