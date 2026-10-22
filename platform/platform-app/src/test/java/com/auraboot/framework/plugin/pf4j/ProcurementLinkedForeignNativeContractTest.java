package com.auraboot.framework.plugin.pf4j;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Hermetic fixture-authority counterexamples; not PG, PF4J or browser acceptance. */
class ProcurementLinkedForeignNativeContractTest {
    @Test void foreignSnapshotRosterRetainsEveryOriginalModelAndAddsPermanentInquiryLedger() {
        var models=ProcurementLinkedForeignNativeFixture.foreignSnapshotModels();
        var expected=Set.of("pr_supplier_common","pr_supplier_pcba_contact","s2p_sourcing_event","pr_purchase_request","s2p_bid",
                "pr_purchase_order","pr_purchase_order_line","qo_procurement_membership_common","qo_procurement_quote_line_common","qo_procurement_history_common",
                "qo_procurement_allocation_common","qo_procurement_execution_common","qo_procurement_address_common","qo_procurement_shipment_common",
                "qo_procurement_shipment_line_common","qo_procurement_receipt_common","qo_procurement_reminder_common","qo_procurement_submission_common",
                "qo_procurement_submission_line_common","qo_procurement_category_policy_common","qo_procurement_category_supplier_common",
                "qo_procurement_inquiry_request_common");
        assertEquals(22,models.size());assertEquals(expected,Set.copyOf(models));
        assertThrows(UnsupportedOperationException.class,()->models.add("unexpected_model"));
    }

    @Test void foreignSnapshotMetadataMatchesActualDualTenantReaderPidAndTableShape() {
        var actual=Map.<String,Object>of("id",17L,"pid","actual-model-pid","tenant_id",29L,"status","published");
        assertEquals(Map.of("pid","actual-model-pid","tableName","mt_actual_table"),
                ProcurementLinkedForeignNativeFixture.foreignSnapshotMetadata(actual,"mt_actual_table"));
        for(var invalid:List.of(Map.<String,Object>of(),Map.<String,Object>of("pid",""),Map.<String,Object>of("pid",17L)))
            assertThrows(AssertionError.class,()->ProcurementLinkedForeignNativeFixture.foreignSnapshotMetadata(invalid,"mt_actual_table"));
    }

    @Test void inquiryLedgerSnapshotPreservesCompleteCanonicalRowAndTypedJsonbFacts() {
        var mapper=new ObjectMapper();
        String raw="""
                {"pid":"request-pid","tenant_id":17,"row_version":2,"qo_pr_inquiry_request_managed":true,
                 "qo_pr_inquiry_request_state":"accepted","qo_pr_inquiry_request_key":"123",
                 "qo_pr_inquiry_request_request_json":{"requestId":"001","title":"true","selected":[{"requisitionId":"123","entryId":"7"}]},
                 "qo_pr_inquiry_request_result_json":{"items":[],"exactQuantity":0.12345678901234567890123456789},
                 "updated_at":null,"unrelated_preserved_column":"null"}
                """;
        var row=ProcurementLinkedForeignNativeFixture.canonicalSnapshotRow(mapper,raw);
        assertEquals(10,row.size());assertEquals("request-pid",row.get("pid"));
        assertEquals(17L,row.get("tenant_id"));assertEquals(2L,row.get("row_version"));
        assertEquals(Boolean.TRUE,row.get("qo_pr_inquiry_request_managed"));
        assertEquals("accepted",row.get("qo_pr_inquiry_request_state"));assertEquals("123",row.get("qo_pr_inquiry_request_key"));
        var request=(Map<?,?>)row.get("qo_pr_inquiry_request_request_json");
        assertEquals("001",request.get("requestId"));assertEquals("true",request.get("title"));
        assertEquals(List.of(Map.of("requisitionId","123","entryId","7")),request.get("selected"));
        var result=(Map<?,?>)row.get("qo_pr_inquiry_request_result_json");
        assertEquals(List.of(),result.get("items"));
        assertEquals(new java.math.BigDecimal("0.12345678901234567890123456789"),result.get("exactQuantity"));
        assertTrue(row.containsKey("updated_at"));assertNull(row.get("updated_at"));
        assertEquals("null",row.get("unrelated_preserved_column"));
        var changed=new LinkedHashMap<>(row);changed.put("row_version",3L);assertNotEquals(row,changed);
        var missing=new LinkedHashMap<>(row);missing.remove("qo_pr_inquiry_request_request_json");assertNotEquals(row,missing);
    }

    @Test void modernPrivateCryptoAdmissionUsesExplicitV2InsteadOfRebrandingLegacyInput() {
        var json=new ObjectMapper();
        var value=json.createObjectNode().put("kind","procurement-linked-foreign-native-admission-v2")
                .put("schemaVersion",2).put("fixtureEnabled",true);
        ProcurementLinkedForeignNativeFixture.requireSelectedInput(value);
    }

    @Test void admissionVersionsKeepLegacyMainAndPrivateSourceFamiliesSeparate() {
        var json=new ObjectMapper();
        var legacy=json.createObjectNode().put("kind","procurement-linked-foreign-native-admission-v1")
                .put("schemaVersion",1).put("fixtureEnabled",true);
        assertEquals("50924063538e4181cb601300f0c26c6bbfcce2f0",ProcurementLinkedForeignNativeAdmission.mainBase(legacy));
        assertEquals(ProcurementLinkedForeignNativeAdmission.SOURCES,ProcurementLinkedForeignNativeAdmission.sources(legacy));
        var modern=legacy.deepCopy().put("kind","procurement-linked-foreign-native-admission-v2").put("schemaVersion",2);
        assertEquals("d60a9f0acdfce618d4817a7a74da24d61f8b2e5d",ProcurementLinkedForeignNativeAdmission.mainBase(modern));
        assertEquals(10,ProcurementLinkedForeignNativeAdmission.sources(modern).size());
        assertTrue(ProcurementLinkedForeignNativeAdmission.sources(modern).contains(ProcurementLinkedForeignNativeAdmission.CRYPTO_SOURCE));
        for(var invalid:List.of(modern.deepCopy().put("kind","procurement-linked-foreign-native-admission-v1"),
                legacy.deepCopy().put("schemaVersion",2),modern.deepCopy().put("schemaVersion","2"),
                modern.deepCopy().put("schemaVersion",3),modern.deepCopy().put("fixtureEnabled",1)))
            assertThrows(AssertionError.class,()->ProcurementLinkedForeignNativeFixture.requireSelectedInput(invalid));
    }

    @Test void privateCryptoMustUseExactAppProofAndSpringAliasBeforeFullS06Admission() throws Exception {
        var json=new ObjectMapper();Path app=Files.createDirectory(root.resolve("app"));
        Path proofPath=Files.writeString(root.resolve("proof.json"),"inert-proof");
        Path otherApp=Files.createDirectory(root.resolve("foreign-app"));
        Path otherProof=Files.writeString(root.resolve("other-proof.json"),"inert-other-proof");
        var input=json.createObjectNode().put("encryptionMode","encrypted").put("encryptionConfigProofSha256","a".repeat(64));
        var proof=json.createObjectNode().put("keySource","private-additional-properties");
        var env=new LinkedHashMap<String,String>(Map.of("S06_NATIVE_APPLICATION_ROOT",app.toString(),
                "S06_NATIVE_ENCRYPTION_PROOF_PATH",proofPath.toString(),"S06_NATIVE_ENCRYPTION_PROOF_SHA256","a".repeat(64),
                "S06_NATIVE_FIELD_ENCRYPTION_KEY","synthetic-key","PROCUREMENT_HOST_FIELD_ENCRYPTION_KEY","synthetic-key"));
        ProcurementLinkedForeignNativeAdmission.validatePrivateCryptoBindings(input,proof,app,proofPath,"synthetic-key",env);
        for(var drift:List.of(Map.entry("S06_NATIVE_APPLICATION_ROOT",otherApp.toString()),
                Map.entry("S06_NATIVE_ENCRYPTION_PROOF_PATH",otherProof.toString()),
                Map.entry("S06_NATIVE_ENCRYPTION_PROOF_SHA256","b".repeat(64)),
                Map.entry("S06_NATIVE_FIELD_ENCRYPTION_KEY","wrong-key"),
                Map.entry("PROCUREMENT_HOST_FIELD_ENCRYPTION_KEY","wrong-key"))) {
            var bad=new LinkedHashMap<>(env);bad.put(drift.getKey(),drift.getValue());
            var rejection=assertThrows(AssertionError.class,()->ProcurementLinkedForeignNativeAdmission.validatePrivateCryptoBindings(input,proof,app,proofPath,"synthetic-key",bad));
            assertFalse(rejection.getMessage().contains("synthetic-key") || rejection.getMessage().contains("wrong-key"),
                    "Matching key material must never enter assertion diagnostics");
        }
        var absent=new LinkedHashMap<>(env);absent.remove("S06_NATIVE_FIELD_ENCRYPTION_KEY");
        assertThrows(IllegalStateException.class,()->ProcurementLinkedForeignNativeAdmission.validatePrivateCryptoBindings(input,proof,app,proofPath,"synthetic-key",absent));
        for(String source:List.of("environment","unknown",""))
            assertThrows(AssertionError.class,()->ProcurementLinkedForeignNativeAdmission.validatePrivateCryptoBindings(input,proof.deepCopy().put("keySource",source),app,proofPath,"synthetic-key",env));
        assertThrows(AssertionError.class,()->ProcurementLinkedForeignNativeAdmission.validatePrivateCryptoBindings(input.deepCopy().put("encryptionMode","dev-passthrough"),proof,app,proofPath,"synthetic-key",env));
    }

    @Test void v2ClassSealRequiresCryptoOuterAndInnerWithExactDigests() throws Exception {
        var json=new ObjectMapper();Path classes=Files.createDirectory(root.resolve("v2-classes"));
        var input=json.createObjectNode().put("kind","procurement-linked-foreign-native-admission-v2").put("schemaVersion",2);
        var sources=ProcurementLinkedForeignNativeAdmission.sources(input);
        var declared=new LinkedHashMap<String,String>();
        for(String source:sources) {
            Path file=classes.resolve(source.substring("platform/src/test/java/".length()).replace(".java",".class"));
            Files.createDirectories(file.getParent());Files.writeString(file,"inert-class-probe");declared.put(file.toRealPath().toString(),ProcurementPublicSourceNativeFixture.sha(file));
        }
        Path app=classes.resolve("com/auraboot/framework/application/TestApplication.class");Files.createDirectories(app.getParent());Files.writeString(app,"app-probe");declared.put(app.toRealPath().toString(),ProcurementPublicSourceNativeFixture.sha(app));
        Path crypto=classes.resolve(ProcurementLinkedForeignNativeAdmission.CRYPTO_SOURCE.substring("platform/src/test/java/".length()).replace(".java",".class"));
        Path inner=crypto.resolveSibling("S06NativeEncryptionAdmission$InertNested.class");Files.writeString(inner,"inert-inner");declared.put(inner.toRealPath().toString(),ProcurementPublicSourceNativeFixture.sha(inner));
        ProcurementLinkedForeignNativeAdmission.validateClassRoster(json.valueToTree(declared),classes,sources);
        for(Path required:List.of(crypto,inner)) {
            var missing=new LinkedHashMap<>(declared);missing.remove(required.toRealPath().toString());
            assertThrows(AssertionError.class,()->ProcurementLinkedForeignNativeAdmission.validateClassRoster(json.valueToTree(missing),classes,sources));
        }
        Files.writeString(inner,"changed");
        assertThrows(AssertionError.class,()->ProcurementLinkedForeignNativeAdmission.validateClassRoster(json.valueToTree(declared),classes,sources));
    }

    @Test void controlledOrderWriteContractIncludesExplicitStrictSourceLinkSaveOptions() {
        assertEquals(ProcurementLinkedForeignNativeAdmission.MAIN_BASE, ProcurementLinkedForeignNativeFixture.MAIN_BASE,
                "Producer prepare and admission must share the same exact Main authority");
        var fixture = new ProcurementLinkedForeignNativeFixture();
        fixture.connection = "controlled-connection";
        fixture.provider = "controlled-account";
        var contract = fixture.orderContract();
        assertControlledOrderWriteContract(contract);
        var originalMissingOptions = new LinkedHashMap<>(contract);
        originalMissingOptions.remove("saveOptions");
        assertThrows(AssertionError.class, () -> assertControlledOrderWriteContract(originalMissingOptions));
        var invalid = new LinkedHashMap<>(contract);
        invalid.put("saveOptions", Map.of());
        assertThrows(AssertionError.class, () -> assertControlledOrderWriteContract(invalid));
    }

    private static void assertControlledOrderWriteContract(Map<String,Object> contract) {
        assertEquals(Set.of("connectionPid", "accountId", "formId", "markerField", "orderIdField", "billNoField", "linesField",
                "headerFields", "lineFields", "supplierReferenceFormId", "organizationValues", "currencyValues",
                "paymentTermValues", "unitValues", "lookupAbsenceAuthoritative", "saveCreatesOrder", "saveOptions"), contract.keySet());
        assertEquals(Map.of("fixedHeaderFields", Map.of("BillType.Number", "CONTROLLED-PO"),
                "fixedLineFields", Map.of("SourceType", "CONTROLLED-SOURCE"), "sourceLink", Map.of(
                "entityKey", "Lines_Link", "ruleId", "controlled-rule", "sourceTableNumber", "CONTROLLED_SOURCE_ENTRY",
                "fieldKeys", Map.of("ruleId", "Lines_Link_Rule", "sourceTableNumber", "Lines_Link_Table",
                        "sourceBillId", "Lines_Link_Bill", "sourceEntryId", "Lines_Link_Entry",
                        "originalBaseQty", "Lines_Link_OldQty", "baseQty", "Lines_Link_Qty"),
                "baseUnitRatios", Map.of("unit-local", "2.5"), "originalQuantityMode", "allocatedBaseQuantity")),
                contract.get("saveOptions"));
    }

    @Test void quantitiesCompareExactlyWithoutTreatingScaleAsBusinessDifference() {
        ProcurementLinkedForeignNativeFixture.assertDecimal("300.000001", "300.000001000000");
        ProcurementLinkedForeignNativeFixture.assertDecimal("250", new java.math.BigDecimal("250.000000000000"));
        assertThrows(AssertionError.class, () -> ProcurementLinkedForeignNativeFixture.assertDecimal("300.000001", "300.000002"));
        assertThrows(AssertionError.class, () -> ProcurementLinkedForeignNativeFixture.assertDecimal("300.000001", "300.000001000001"));
        assertThrows(AssertionError.class, () -> ProcurementLinkedForeignNativeFixture.assertDecimal("250", "250.000000000000000000000000000001"));
    }

    @Test void quoteAndAllocationUseCurrentClassifiedRevisionOnlyWhenFrozenLineMatches() {
        var source = new LinkedHashMap<String, Object>(Map.of("pid", "source", "qo_pr_source_state", "active",
                "qo_pr_source_revision", "1.000000000000"));
        var line = new LinkedHashMap<String, Object>(Map.of("qo_pr_quote_line_source_pid", "source",
                "qo_pr_quote_line_quote_pid", "quote", "qo_pr_quote_line_source_revision", 1));
        assertEquals(1L, ProcurementLinkedForeignNativeFixture.matchingSourceRevision(source, line, "source", "quote"));
        line.put("qo_pr_quote_line_source_revision", 0);
        assertThrows(AssertionError.class, () -> ProcurementLinkedForeignNativeFixture.matchingSourceRevision(source, line, "source", "quote"));
        line.put("qo_pr_quote_line_source_revision", 1);
        assertThrows(AssertionError.class, () -> ProcurementLinkedForeignNativeFixture.matchingSourceRevision(source, line, "foreign", "quote"));
        assertThrows(AssertionError.class, () -> ProcurementLinkedForeignNativeFixture.matchingSourceRevision(source, line, "source", "foreign-quote"));
        source.put("qo_pr_source_revision", "2");
        assertThrows(AssertionError.class, () -> ProcurementLinkedForeignNativeFixture.matchingSourceRevision(source, line, "source", "quote"));
    }

    @Test void failedActionPersistsSafeResponseBeforeOriginalStatusAssertion() throws Exception {
        var fixture = new ProcurementLinkedForeignNativeFixture();
        fixture.json = new ObjectMapper(); fixture.caseEvidence = root;
        fixture.tenant = 17L; fixture.userId = 1L; fixture.memberId = 3L;
        fixture.actor = "synthetic-actor"; fixture.provider = "synthetic-provider";
        fixture.mvc = org.mockito.Mockito.mock(org.springframework.test.web.servlet.MockMvc.class);
        var actions = org.mockito.Mockito.mock(org.springframework.test.web.servlet.ResultActions.class);
        var result = org.mockito.Mockito.mock(org.springframework.test.web.servlet.MvcResult.class);
        var response = new org.springframework.mock.web.MockHttpServletResponse(); response.setStatus(400);
        response.getWriter().write("{\"code\":\"35000\",\"context\":{\"messageKey\":\"$i18n:qo_pr.error.source_changed\",\"detail\":\"private-body\"},\"token\":\"private-token\"}");
        org.mockito.Mockito.when(fixture.mvc.perform(org.mockito.ArgumentMatchers.any())).thenReturn(actions);
        org.mockito.Mockito.when(actions.andReturn()).thenReturn(result);
        org.mockito.Mockito.when(result.getResponse()).thenReturn(response);
        var method = ProcurementLinkedForeignNativeFixture.class.getDeclaredMethod("action", String.class,
                String.class, String.class, Map.class); method.setAccessible(true);
        try {
            var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                    () -> method.invoke(fixture, "synthetic-bearer", "qo_procurement_quote_common:save_draft", null, Map.of("revision", 0)));
            assertInstanceOf(org.opentest4j.AssertionFailedError.class, failure.getCause());
            var artifact = root.resolve("linked-public-action-1.json"); assertTrue(Files.isRegularFile(artifact));
            var safe = fixture.json.readTree(artifact.toFile());
            assertEquals(400, safe.path("httpStatus").asInt()); assertEquals("35000", safe.path("code").asText());
            assertEquals("$i18n:qo_pr.error.source_changed", safe.path("messageKey").asText());
            String raw = Files.readString(artifact);
            assertFalse(raw.contains("private-body") || raw.contains("private-token") || raw.contains("synthetic-bearer"));
        } finally { com.auraboot.framework.application.tenant.MetaContext.clear(); }
    }

    @Test void controlledSourceMaterialUsesExplicitConnectionAccountAndCanonicalMaterialReference() {
        var facts = ProcurementLinkedForeignNativeFixture.materialReference("connection-pid", "account-id", "product-pid");
        assertEquals(Set.of("customer_pcba_ref_external_system", "customer_pcba_ref_instance_key",
                "customer_pcba_ref_account_id", "customer_pcba_ref_external_form_id", "customer_pcba_ref_external_id",
                "customer_pcba_ref_local_model_code", "customer_pcba_ref_local_pid"), facts.keySet());
        assertEquals("KINGDEE", facts.get("customer_pcba_ref_external_system"));
        assertEquals("connection-pid", facts.get("customer_pcba_ref_instance_key"));
        assertEquals("account-id", facts.get("customer_pcba_ref_account_id"));
        assertEquals("BD_MATERIAL", facts.get("customer_pcba_ref_external_form_id"));
        assertEquals("900", facts.get("customer_pcba_ref_external_id"));
        assertEquals("prod_product", facts.get("customer_pcba_ref_local_model_code"));
        assertEquals("product-pid", facts.get("customer_pcba_ref_local_pid"));
        ProcurementLinkedForeignInitialization.validatePayload("customer_pcba_int_external_ref", facts);
        assertThrows(AssertionError.class, () -> ProcurementLinkedForeignNativeFixture.materialReference(null, "a", "p"));
        assertThrows(AssertionError.class, () -> ProcurementLinkedForeignNativeFixture.materialReference("c", "", "p"));
    }

    @Test void sourceResponseDiagnosticNeverPersistsCredentialOrRawErrorText() throws Exception {
        var json = new ObjectMapper();
        var body = json.readTree("{\"code\":\"35000\",\"message\":\"secret-response\",\"token\":\"secret-token\",\"context\":{\"detail\":\"private-detail\",\"messageKey\":\"$i18n:qo_pr.error.material_mapping_required\"}}");
        var safe = ProcurementLinkedForeignNativeFixture.safePublicResponse(400, "qo_procurement_task_common:sync_sources", "task-pid", body);
        assertEquals("35000", safe.get("code"));
        assertEquals("$i18n:qo_pr.error.material_mapping_required", safe.get("messageKey"));
        String serialized = json.writeValueAsString(safe);
        assertFalse(serialized.contains("secret-response") || serialized.contains("secret-token") || serialized.contains("private-detail"));
    }

    @Test void optInLinkedObserverWinsExactInheritedAutowiredDependencyWithoutRemovingOriginal() throws Exception {
        var factory = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        var configuration = new ProcurementPluginHostRealDbIT.PollerIsolation();
        var linked = configuration.linkedSourceObservation();
        var original = new ProcurementPublicSourceNativeFixture.SourceWriteObservation();
        var linkedDefinition = new org.springframework.beans.factory.support.RootBeanDefinition(
                ProcurementPublicSourceNativeFixture.SourceWriteObservation.class);
        linkedDefinition.setInstanceSupplier(() -> linked);
        linkedDefinition.setPrimary(ProcurementPluginHostRealDbIT.PollerIsolation.class
                .getDeclaredMethod("linkedSourceObservation").isAnnotationPresent(org.springframework.context.annotation.Primary.class));
        var originalDefinition = new org.springframework.beans.factory.support.RootBeanDefinition(
                ProcurementPublicSourceNativeFixture.SourceWriteObservation.class);
        originalDefinition.setInstanceSupplier(() -> original);
        factory.registerBeanDefinition("linkedSourceObservation", linkedDefinition);
        factory.registerBeanDefinition("sourceWriteObservation", originalDefinition);
        var field = ProcurementPublicSourceNativeFixture.class.getDeclaredField("observed");
        var descriptor = new org.springframework.beans.factory.config.DependencyDescriptor(field, true);
        assertSame(linked, factory.resolveDependency(descriptor, "linkedFixture"));
        assertSame(original, factory.getBean("sourceWriteObservation"));
        assertEquals(2, factory.getBeansOfType(ProcurementPublicSourceNativeFixture.SourceWriteObservation.class).size());
        var ambiguous = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        ambiguous.registerBeanDefinition("linkedSourceObservation", new org.springframework.beans.factory.support.RootBeanDefinition(
                ProcurementPublicSourceNativeFixture.SourceWriteObservation.class));
        ambiguous.registerBeanDefinition("sourceWriteObservation", new org.springframework.beans.factory.support.RootBeanDefinition(
                ProcurementPublicSourceNativeFixture.SourceWriteObservation.class));
        assertThrows(org.springframework.beans.factory.NoUniqueBeanDefinitionException.class,
                () -> ambiguous.resolveDependency(descriptor, "linkedFixture"));
    }

    @TempDir Path root;
    @Test void retainedRolesDoNotInheritInitializerOrBuyerAuthority() {
        assertEquals(12,ProcurementLinkedForeignNativeFixture.buyerPermissions(false).size());
        assertEquals(11,ProcurementLinkedForeignNativeFixture.buyerPermissions(true).size());
        var positive=Set.copyOf(ProcurementLinkedForeignNativeFixture.buyerPermissions(false));
        var negative=new java.util.HashSet<>(positive);negative.remove("model.pr_supplier_common.read");
        assertEquals(negative,Set.copyOf(ProcurementLinkedForeignNativeFixture.buyerPermissions(true)));
        assertEquals(Set.of("meta.command.execute","qo.srm.use"),Set.copyOf(ProcurementLinkedForeignNativeFixture.SUPPLIER_PERMISSIONS));
        var initializer=ProcurementLinkedForeignNativeFixture.INITIALIZER_PERMISSIONS;
        assertEquals(initializer.size(),Set.copyOf(initializer).size());assertFalse(initializer.contains("*"));assertFalse(initializer.contains("qo.srm.use"));
        assertTrue(initializer.containsAll(ProcurementPublicSourceNativeFixture.ORDER_PERMISSIONS));
        assertThrows(IllegalStateException.class,()->new ProcurementLinkedForeignNativeFixture().fixture(
                "qo_procurement_task_common:sync_sources",()->{fail("Private writer callback must never run");return null;}));
    }
    @Test void retainedActorCannotAliasInternalMemberIdOrPersistBearer() {
        var actor=new LinkedHashMap<String,Object>(Map.of("email","synthetic@example.invalid","userPid","actual-user",
                "memberId","123","memberPid","actual-member","roleCode","supplier","bearer","not-for-evidence"));
        var safe=ProcurementLinkedForeignNativeFixture.receiptActor(actor);
        assertEquals("actual-member",safe.get("memberPid"));assertFalse(safe.containsKey("memberId"));assertFalse(safe.containsKey("bearer"));
        actor.remove("memberPid");assertThrows(AssertionError.class,()->ProcurementLinkedForeignNativeFixture.receiptActor(actor));
    }
    @Test void publicForeignRejectionUsesHostI18nEnvelopeInsteadOfDomainExceptionText() {
        var json=new ObjectMapper();var correct=json.valueToTree(Map.of("code","35000","context",Map.of("messageKey","$i18n:qo_pr.error.phone_not_registered")));
        ((com.fasterxml.jackson.databind.node.ObjectNode)correct).putNull("data");
        ProcurementLinkedForeignNativeFixture.requireForeignRejection(correct);
        var raw=correct.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)raw.path("context")).put("messageKey","qo_pr.error.phone_not_registered");
        assertThrows(AssertionError.class,()->ProcurementLinkedForeignNativeFixture.requireForeignRejection(raw));
        var leaked=correct.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)leaked).putObject("data").put("foreignContactPid","not-for-this-account");
        assertThrows(AssertionError.class,()->ProcurementLinkedForeignNativeFixture.requireForeignRejection(leaked));
    }
    @Test void initializersCannotWriteFormalOrdersOrBorrowProductionNames() {
        assertFalse(ProcurementLinkedForeignInitialization.FIELDS.containsKey("pr_purchase_order"));
        assertFalse(ProcurementLinkedForeignInitialization.FIELDS.containsKey("pr_purchase_order_line"));
        for(var entry:ProcurementLinkedForeignInitialization.FIELDS.entrySet()) {
            String code=ProcurementLinkedForeignInitialization.command(entry.getKey());
            assertTrue(code.startsWith(entry.getKey()+":") && code.endsWith(":init_linked_fixture") && code.length()<=64);
            var facts=new LinkedHashMap<String,Object>();for(String field:entry.getValue())facts.put(field,"synthetic");
            ProcurementLinkedForeignInitialization.validatePayload(entry.getKey(),facts);
            facts.put("clientWriter","native:create");assertThrows(IllegalArgumentException.class,()->ProcurementLinkedForeignInitialization.validatePayload(entry.getKey(),facts));
        }
        assertThrows(IllegalArgumentException.class,()->ProcurementLinkedForeignInitialization.command("pr_purchase_order_line"));
    }
    @Test void exactCompiledFamiliesRejectMissingExtraChangedAndSymlinkedFiles() throws Exception {
        var json=new ObjectMapper();Path classes=Files.createDirectory(root.resolve("classes"));
        var declared=new LinkedHashMap<String,String>();
        for(String source:ProcurementLinkedForeignNativeAdmission.SOURCES) {
            Path file=classes.resolve(source.substring("platform/src/test/java/".length()).replace(".java",".class"));
            Files.createDirectories(file.getParent());Files.writeString(file,"inert-class-probe");declared.put(file.toRealPath().toString(),ProcurementPublicSourceNativeFixture.sha(file));
        }
        Path app=classes.resolve("com/auraboot/framework/application/TestApplication.class");Files.createDirectories(app.getParent());Files.writeString(app,"app-probe");declared.put(app.toRealPath().toString(),ProcurementPublicSourceNativeFixture.sha(app));
        ProcurementLinkedForeignNativeAdmission.validateClassRoster(json.valueToTree(declared),classes);
        var missing=new LinkedHashMap<>(declared);missing.remove(app.toRealPath().toString());assertThrows(AssertionError.class,()->ProcurementLinkedForeignNativeAdmission.validateClassRoster(json.valueToTree(missing),classes));
        var extra=new LinkedHashMap<>(declared);extra.put("/foreign.class","a".repeat(64));assertThrows(AssertionError.class,()->ProcurementLinkedForeignNativeAdmission.validateClassRoster(json.valueToTree(extra),classes));
        Files.writeString(app,"changed");assertThrows(AssertionError.class,()->ProcurementLinkedForeignNativeAdmission.validateClassRoster(json.valueToTree(declared),classes));
        Files.delete(app);Files.createSymbolicLink(app,classes.resolve("com/auraboot/framework/plugin/pf4j/ProcurementPluginHostRealDbIT.class"));
        assertThrows(AssertionError.class,()->ProcurementLinkedForeignNativeAdmission.validateClassRoster(json.valueToTree(declared),classes));
    }
    @Test void declaredOwnerCopiesPreserveProductionAndOnlyAppendExactCreateWriters() throws Exception {
        var json=new ObjectMapper();var paths=new ArrayList<Path>();
        for(int index=0;index<23;index++) {
            Path owner=Files.createDirectory(root.resolve("owner-"+index));Files.createDirectories(owner.resolve("config/commands"));
            Files.writeString(owner.resolve("plugin.json"),json.writeValueAsString(Map.of("pluginId","com.test.owner-"+index,"resourceDirs",Map.of("commands","config/commands"))));paths.add(owner);
        }
        Path owner=paths.getFirst();Files.createDirectories(owner.resolve("config/fields"));Files.createDirectories(owner.resolve("config/bindings"));
        var models=json.createArrayNode();var bindings=json.createArrayNode();
        for(var entry:ProcurementLinkedForeignInitialization.FIELDS.entrySet()) {
            var model=models.addObject().put("code",entry.getKey());
            var policy=model.putObject("extension").putObject("recordCommandWriters");policy.put("field","managed");
            var commands=policy.putObject("commands");commands.putArray("create").add("native:create");commands.putArray("update").add("native:update");commands.putArray("delete");
            for(String field:entry.getValue()) {
                bindings.addObject().put("modelCode",entry.getKey()).put("fieldCode",field);
                var definition=json.createObjectNode().put("code",field).put("dataType","string");
                definition.putArray("allowedWriterCommands").add("native:write");definition.putObject("extension").put("systemManaged",true);
                Files.writeString(owner.resolve("config/fields/"+field+".json"),json.writeValueAsString(definition));
            }
        }
        Files.writeString(owner.resolve("config/models.json"),json.writeValueAsString(models));Files.writeString(owner.resolve("config/bindings/all.json"),json.writeValueAsString(bindings));
        var original=ProcurementSupplierUiInitialization.resourceRoster(owner);
        var proof=ProcurementLinkedForeignInitialization.prepare(paths,root.resolve("copy"),json);
        assertEquals(original,ProcurementSupplierUiInitialization.resourceRoster(owner));
        var copies=(List<Map<String,Object>>)proof.get("ownerDirectories");Path copy=Path.of(copies.getFirst().get("testDirectory").toString());
        for(var model:json.readTree(copy.resolve("config/models.json").toFile())) {
            assertEquals(List.of("native:create",ProcurementLinkedForeignInitialization.command(model.path("code").asText())),json.convertValue(model.path("extension").path("recordCommandWriters").path("commands").path("create"),List.class));
            assertEquals(List.of("native:update"),json.convertValue(model.path("extension").path("recordCommandWriters").path("commands").path("update"),List.class));
        }
        var account=json.readTree(copy.resolve("config/fields/customer_pcba_ref_account_id.json").toFile());
        assertEquals(List.of("native:write",ProcurementLinkedForeignInitialization.command("customer_pcba_int_external_ref")),json.convertValue(account.path("allowedWriterCommands"),List.class));
        var duplicate=new ArrayList<>(paths);duplicate.set(22,paths.getFirst());assertThrows(IllegalArgumentException.class,()->ProcurementLinkedForeignInitialization.prepare(duplicate,root.resolve("duplicate"),json));
        assertThrows(IllegalArgumentException.class,()->ProcurementSupplierUiInitialization.prepareOwned(paths,root.resolve("borrowed"),json,ProcurementLinkedForeignInitialization.FIELDS,model->"native:create"));
    }
}
