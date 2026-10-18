package com.auraboot.framework.plugin.pf4j;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Hermetic protocol/ownership contracts; none execute the retained PG Native selector. */
class ProcurementQuoteLifecycleContractTest {
    private final com.fasterxml.jackson.databind.ObjectMapper json=ProcurementQuoteLifecycleErp.JSON;
    private JsonNode query() {
        return json.valueToTree(Map.of("data",Map.of("FormId","BD_Supplier","FieldKeys",ProcurementQuoteLifecycleErp.FIELDS,
                "FilterString","FSupplierId=701","OrderString","FSupplierId ASC","StartRow",0,"Limit",100,"TopRowCount",0)));
    }
    private JsonNode login() {return json.valueToTree(Map.of("acctID","test-account","username","test-account","appId","test-account","appSecret",ProcurementQuoteLifecycleErp.SECRET,"lcid",2052));}
    @Test void realLoopbackProtocolReturnsOnlyControlledCompleteSupplierAndConditions() throws Exception {
        try(var erp=new ProcurementQuoteLifecycleErp("test-account")) {
            erp.start();var http=HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(2)).build();
            var auth=http.send(HttpRequest.newBuilder(URI.create(erp.baseUrl()+ProcurementQuoteLifecycleErp.LOGIN))
                    .timeout(java.time.Duration.ofSeconds(3)).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(login()))).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,auth.statusCode());assertEquals(1,json.readTree(auth.body()).path("LoginResultType").intValue());
            var cookie=auth.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0];
            var response=http.send(HttpRequest.newBuilder(URI.create(erp.baseUrl()+ProcurementQuoteLifecycleErp.QUERY))
                    .timeout(java.time.Duration.ofSeconds(3)).header("Cookie",cookie).POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(query()))).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode());assertEquals(6,json.readTree(response.body()).get(0).size());
            assertEquals(701,json.readTree(response.body()).get(0).get(0).intValue());
            var denied=http.send(HttpRequest.newBuilder(URI.create(erp.baseUrl()+ProcurementQuoteLifecycleErp.ROOT+"DynamicFormService.Save.common.kdsvc"))
                    .timeout(java.time.Duration.ofSeconds(3)).header("Cookie",cookie).POST(HttpRequest.BodyPublishers.ofString("{}",StandardCharsets.UTF_8)).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(400,denied.statusCode());assertEquals(1,erp.denied.get());
        }
    }
    @Test void unknownFormIdentityFieldAndCookieNeverReadAControlledFact() throws Exception {
        try(var erp=new ProcurementQuoteLifecycleErp("test-account")) {
            assertThrows(IllegalArgumentException.class,()->erp.reply(ProcurementQuoteLifecycleErp.QUERY,query(),null));
            var foreign=(ObjectNode)query().deepCopy();((ObjectNode)foreign.path("data")).put("FilterString","FSupplierId=702");
            assertThrows(IllegalArgumentException.class,()->erp.reply(ProcurementQuoteLifecycleErp.QUERY,foreign,"lifecycle=authenticated"));
            var field=(ObjectNode)query().deepCopy();((ObjectNode)field.path("data")).put("FieldKeys","FSupplierId,FPassword");
            assertThrows(IllegalArgumentException.class,()->erp.reply(ProcurementQuoteLifecycleErp.QUERY,field,"lifecycle=authenticated"));
            for(String form:List.of("PUR_PurchaseOrder","BD_PaymentCondition"))
                assertThrows(IllegalArgumentException.class,()->erp.reply(ProcurementQuoteLifecycleErp.VIEW,json.valueToTree(Map.of("formid",form,"data",Map.of("Id","999"))),"lifecycle=authenticated"));
            assertEquals(0,erp.queries.get());assertEquals(0,erp.views.get());
        }
    }
    @Test void twoDistinctFinanceEntriesAndLastExactRuleAreNotAliased() throws Exception {
        var supplier=ProcurementQuoteLifecycleErp.supplier();var finance=supplier.path("SupplierFinance");assertEquals(2,finance.size());
        assertEquals(Set.of("1101","1102"),Set.of(finance.get(0).path("Id").asText(),finance.get(1).path("Id").asText()));
        assertEquals("801",finance.get(0).path("PayCondition_Id").asText());assertEquals("802",finance.get(1).path("PayCondition_Id").asText());
        for(String id:List.of("801","802")) {
            var condition=ProcurementQuoteLifecycleErp.condition(id);assertEquals(id,condition.path("Id").asText());assertEquals(2,condition.path("PayRecCondtionEntry").size());
            assertEquals(0,new BigDecimal("59.99999999999999999999999999999").compareTo(condition.path("PayRecCondtionEntry").get(1).path("Rate").decimalValue()));
            assertTrue(condition.path("ISINSTALLMENT").isBoolean());assertTrue(condition.path("PayRecCondtionEntry").get(0).path("FWorkCalID").isNull());
        }
        ((ObjectNode)finance.get(0).path("PayCondition")).put("Id",999);assertEquals(802,finance.get(1).path("PayCondition").path("Id").intValue());
    }
    @Test void independentTypedOraclePreservesStringBooleanAndEveryRule() throws Exception {
        var original=ProcurementQuoteLifecycleErp.condition("801");var same=original.deepCopy();((ObjectNode)same).put("Id",new BigDecimal("801.0"));
        assertTrue(ProcurementQuoteLifecycleNativeIT.sameFacts(original,same));
        var bool=original.deepCopy();bool.put("ISINSTALLMENT",1);assertFalse(ProcurementQuoteLifecycleNativeIT.sameFacts(original,bool));
        var text=original.deepCopy();text.put("Number","801");var number=text.deepCopy();number.put("Number",801);assertFalse(ProcurementQuoteLifecycleNativeIT.sameFacts(text,number));
        var truncated=original.deepCopy();((com.fasterxml.jackson.databind.node.ArrayNode)truncated.path("PayRecCondtionEntry")).remove(1);
        assertFalse(ProcurementQuoteLifecycleNativeIT.sameFacts(original,truncated));
    }
    @Test void selectionRequiresExactlyMatchingExplicitConditionAndNoFirstRowFallback() {
        var options=List.of(Map.of("financeEntryId","1101","conditionId","801","sourceDigest","a".repeat(64)),Map.of("financeEntryId","1102","conditionId","802","sourceDigest","a".repeat(64)));
        var view=json.valueToTree(Map.of("paymentConditionOptions",options));
        assertEquals("1102",ProcurementQuoteLifecycleNativeIT.selection(view,"802").get("financeEntryId"));
        assertThrows(AssertionError.class,()->ProcurementQuoteLifecycleNativeIT.selection(view,"803"));
        assertThrows(AssertionError.class,()->ProcurementQuoteLifecycleNativeIT.selection(json.valueToTree(Map.of("paymentConditionOptions",List.of(options.get(0),options.get(0)))),"801"));
    }
    @Test void initializationCannotWriteDirectoryPaymentOrAdoptProductionIdentity() {
        assertEquals(5,ProcurementQuoteLifecycleNativeIT.INITIAL_MODELS.size());
        for(String model:List.of("pr_supplier_common","pr_supplier_pcba_contact","customer_pcba_int_external_ref"))
            assertThrows(IllegalArgumentException.class,()->ProcurementQuoteLifecycleNativeIT.initCommand(model));
        for(String model:ProcurementQuoteLifecycleNativeIT.INITIAL_MODELS) {
            assertNotNull(ProcurementSupplierUiInitialization.FIELDS.get(model));
            assertTrue(ProcurementQuoteLifecycleNativeIT.initCommand(model).length()<=64);
        }
        assertEquals(List.of("quoteSubmit","quoteDecline","shipmentJourney","shipmentUnknown","closedOrder"),ProcurementSupplierUiFixtureProducerIT.STAGES);
        assertEquals(List.of("meta.command.execute","qo.srm.use","page.page.read"),ProcurementSupplierUiFixtureProducerIT.SUPPLIER_PERMISSIONS);
    }
    @Test void defaultOffDedicatedInputCannotBorrowOldSupplierUiQualification() {
        var input=json.valueToTree(Map.of("kind","procurement-quote-lifecycle-input-v1","schemaVersion",1,"quoteLifecycleEnabled",true,"sourceSet",Map.of("oss",ProcurementQuoteLifecycleNativeIT.MAIN)));
        ProcurementQuoteLifecycleNativeIT.validateInput(input);
        var old=input.deepCopy();((ObjectNode)old).put("kind","procurement-supplier-ui-input-v1");assertThrows(AssertionError.class,()->ProcurementQuoteLifecycleNativeIT.validateInput(old));
        var disabled=input.deepCopy();((ObjectNode)disabled).put("quoteLifecycleEnabled",false);assertThrows(AssertionError.class,()->ProcurementQuoteLifecycleNativeIT.validateInput(disabled));
        var baseline=input.deepCopy();((ObjectNode)baseline.path("sourceSet")).put("oss","50924063538e4181cb601300f0c26c6bbfcce2f0");assertThrows(AssertionError.class,()->ProcurementQuoteLifecycleNativeIT.validateInput(baseline));
        assertEquals(1,Arrays.stream(ProcurementQuoteLifecycleNativeIT.class.getDeclaredMethods()).filter(m->m.isAnnotationPresent(Test.class)).count());
    }
    private ObjectNode submissionResponse(JsonNode revision) {
        var response=json.createObjectNode().put("quotePid","controlled-quote").put("recordPid","controlled-quote")
                .put("state","submitted").put("submissionPid","controlled-submission");response.set("revision",revision);return response;
    }
    @Test void submissionReplayAllowsOnlyCanonicalRevisionNumberStringCompatibility() {
        var first=submissionResponse(json.getNodeFactory().numberNode(5));var replay=submissionResponse(json.getNodeFactory().textNode("5"));
        ProcurementQuoteLifecycleNativeIT.assertSubmissionReplay(first,replay);
        ProcurementQuoteLifecycleNativeIT.assertSubmissionReplay(replay,first);
        ProcurementQuoteLifecycleNativeIT.assertSubmissionReplay(submissionResponse(json.getNodeFactory().numberNode(0)),submissionResponse(json.getNodeFactory().textNode("0")));
        ProcurementQuoteLifecycleNativeIT.assertSubmissionReplay(submissionResponse(json.getNodeFactory().numberNode(Long.MAX_VALUE)),submissionResponse(json.getNodeFactory().textNode(Long.toString(Long.MAX_VALUE))));
    }
    @Test void submissionReplayRejectsNoncanonicalTypesSignsPrecisionAndOverflow() {
        var first=submissionResponse(json.getNodeFactory().numberNode(5));
        var invalid=new ArrayList<JsonNode>();
        for(String value:List.of("", "05", "+5", "-5", "-0", "5.0", "5e0", " 5", "5 ", "9223372036854775808")) invalid.add(json.getNodeFactory().textNode(value));
        invalid.add(json.getNodeFactory().booleanNode(true));invalid.add(json.getNodeFactory().numberNode(new BigDecimal("5.0")));
        invalid.add(json.getNodeFactory().numberNode(5.0));invalid.add(json.getNodeFactory().numberNode(-5));
        invalid.add(json.getNodeFactory().numberNode(new java.math.BigInteger("9223372036854775808")));invalid.add(json.getNodeFactory().nullNode());
        for(JsonNode value:invalid) assertThrows(AssertionError.class,()->ProcurementQuoteLifecycleNativeIT.assertSubmissionReplay(first,submissionResponse(value)),value.getNodeType().name());
    }
    @Test void submissionReplayRequiresExactFourOtherFieldsAndExactFieldSet() {
        var first=submissionResponse(json.getNodeFactory().numberNode(5));
        for(String field:List.of("quotePid","recordPid","state","submissionPid")) {
            var altered=first.deepCopy();altered.put(field,"changed");assertThrows(AssertionError.class,()->ProcurementQuoteLifecycleNativeIT.assertSubmissionReplay(first,altered),field);
        }
        var extra=first.deepCopy();extra.put("ignored",true);assertThrows(AssertionError.class,()->ProcurementQuoteLifecycleNativeIT.assertSubmissionReplay(first,extra));
        var missing=first.deepCopy();missing.remove("recordPid");assertThrows(AssertionError.class,()->ProcurementQuoteLifecycleNativeIT.assertSubmissionReplay(first,missing));
    }
    @Test void submissionReplayRejectsDifferentRevisionEvenWithMatchingAllIdentities() {
        var first=submissionResponse(json.getNodeFactory().numberNode(5));
        assertThrows(AssertionError.class,()->ProcurementQuoteLifecycleNativeIT.assertSubmissionReplay(first,submissionResponse(json.getNodeFactory().textNode("6"))));
    }

}
