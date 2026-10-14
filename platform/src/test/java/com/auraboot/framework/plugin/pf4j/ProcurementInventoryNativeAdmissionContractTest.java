package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.meta.service.DynamicDataService;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Hermetic prerequisite guards and real AOP invocation, not a Native receipt verdict. */
class ProcurementInventoryNativeAdmissionContractTest {
    @Test void matchingCryptoRequiresExplicitManagedValueWithoutHardcodedKeyOrFallback() {
        var properties=ProcurementInventoryReceiptRealDbIT.class.getAnnotation(SpringBootTest.class).properties();
        assertTrue(Arrays.asList(properties).contains("security.field-encryption.key=${PROCUREMENT_RECEIPT_FIELD_ENCRYPTION_KEY}"));
        assertFalse(Arrays.stream(properties).anyMatch(value->value.startsWith("security.field-encryption.key=") && value.contains(":")));
    }
    @Test void originalFourNativeMethodsAndActualArtifactOwnerIdsStayExact() {
        var methods=Arrays.stream(ProcurementInventoryReceiptRealDbIT.class.getDeclaredMethods())
                .filter(method->method.isAnnotationPresent(Test.class)).map(java.lang.reflect.Method::getName).sorted().toList();
        assertEquals(List.of("heldCounterRowLockSkipsExpiredConcurrentClaimAndRollsBackExpiredEffect",
                "postedReceiptLateCasFailureRollsBackReceiverButRetainsPostedStock",
                "registeredReceiptCommandsPersistLineageAndCounterExactlyOnce",
                "replacedDeliveryLeaseRejectsOldReceiverWithoutEffects"),methods);
        assertEquals(Set.of("com.auraboot.inventory","com.auraboot.procurement"),ProcurementInventoryNativeAdmission.PLUGINS);
    }
    @Test void receiptUsesActualRegisteredCommandControllerRoute() {
        var controller=com.auraboot.framework.meta.controller.config.CommandController.class;
        String prefix=controller.getAnnotation(org.springframework.web.bind.annotation.RequestMapping.class).value()[0];
        var routes=Arrays.stream(controller.getDeclaredMethods()).map(method->method.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class))
                .filter(java.util.Objects::nonNull).flatMap(mapping->Arrays.stream(mapping.value())).filter(path->path.contains("{commandCode}")).toList();
        assertEquals(List.of("/execute/{commandCode}"),routes);
        assertEquals(prefix+routes.getFirst().replace("{commandCode}","pr:confirm_purchase_receipt"),
                ProcurementInventoryReceiptRealDbIT.receiptCommandPath("pr:confirm_purchase_receipt"));
    }
    @Test void repeatedFormalImportUsesOriginalTenantCreatorAndAlwaysRestoresCurrentPublicActor() {
        var principals=new java.util.LinkedHashMap<Long,com.auraboot.framework.application.tenant.MetaContext.Snapshot>();
        try {
            com.auraboot.framework.application.tenant.MetaContext.setContext(11L,101L,"creator-a","creator-a");
            com.auraboot.framework.application.tenant.MetaContext.setMemberId(201L);
            var original=com.auraboot.framework.application.tenant.MetaContext.snapshot();
            ProcurementInventoryReceiptRealDbIT.withFormalImportPrincipal(principals,()->assertEquals("creator-a",com.auraboot.framework.application.tenant.MetaContext.getCurrentUserPid()));
            com.auraboot.framework.application.tenant.MetaContext.setContext(11L,102L,"case-two-actor","case-two");
            com.auraboot.framework.application.tenant.MetaContext.setMemberId(202L);
            var current=com.auraboot.framework.application.tenant.MetaContext.snapshot();
            var used=ProcurementInventoryReceiptRealDbIT.withFormalImportPrincipal(principals,()->{
                assertEquals("creator-a",com.auraboot.framework.application.tenant.MetaContext.getCurrentUserPid());
                assertEquals(201L,com.auraboot.framework.application.tenant.MetaContext.getCurrentMemberId());
            });
            assertEquals(original,used);assertEquals(current,com.auraboot.framework.application.tenant.MetaContext.snapshot());
            assertThrows(IllegalStateException.class,()->ProcurementInventoryReceiptRealDbIT.withFormalImportPrincipal(principals,()->{throw new IllegalStateException("controlled import failure");}));
            assertEquals(current,com.auraboot.framework.application.tenant.MetaContext.snapshot());
            com.auraboot.framework.application.tenant.MetaContext.setContext(12L,103L,"creator-b","creator-b");
            com.auraboot.framework.application.tenant.MetaContext.setMemberId(203L);
            ProcurementInventoryReceiptRealDbIT.withFormalImportPrincipal(principals,()->{
                assertEquals(12L,com.auraboot.framework.application.tenant.MetaContext.getCurrentTenantId());
                assertEquals("creator-b",com.auraboot.framework.application.tenant.MetaContext.getCurrentUserPid());
            });
            assertEquals(2,principals.size());
            var foreignCurrent=com.auraboot.framework.application.tenant.MetaContext.snapshot();
            principals.put(12L,original);
            assertThrows(AssertionError.class,()->ProcurementInventoryReceiptRealDbIT.withFormalImportPrincipal(principals,()->fail("Foreign tenant cannot borrow the baseline creator")));
            assertEquals(foreignCurrent,com.auraboot.framework.application.tenant.MetaContext.snapshot());
        } finally {com.auraboot.framework.application.tenant.MetaContext.clear();}
    }
    @Test void prerequisiteActorHasOnlyDeclaredFunctionalAndModelPermissions() {
        var permissions=ProcurementInventoryReceiptRealDbIT.prerequisitePermissions();
        assertEquals(22,permissions.size());assertEquals(22,Set.copyOf(permissions).size());
        assertTrue(permissions.contains("model.qo_procurement_category_supplier_common.read"));
        assertFalse(permissions.contains("*"));assertFalse(permissions.contains("qo.srm.use"));
        assertTrue(permissions.containsAll(List.of("meta.command.execute","qo.procurement.integrate",
                "qo.procurement.manage","prod.product.manage","model.prod_product.create","model.prod_product.update")));
    }
    @Test void syntheticProductionWriterIdentityIsRejectedBeforeItsEffect() {
        var fixture=new ProcurementInventoryReceiptRealDbIT();
        assertThrows(AssertionError.class,()->fixture.fixture("qo_procurement_task_common:dispatch",()->{
            fail("A private production writer cannot execute");return null;}));
    }
    @Test void fourSequentialCaseDirectoriesAlwaysCopyTheUnmodifiedProductionResources() throws Exception {
        var root=java.nio.file.Files.createTempDirectory("inventory-directory-contract-");
        var source=java.nio.file.Files.createDirectory(root.resolve("original"));
        java.nio.file.Files.createDirectories(source.resolve("config/fields"));
        java.nio.file.Files.createDirectories(source.resolve("config/commands"));
        String original="{\"code\":\"qo_pr_contact_state\",\"allowedWriterCommands\":[\"pr_supplier_pcba_contact:sync_directory\"]}";
        java.nio.file.Files.writeString(source.resolve("config/fields/contact.json"),original);
        java.nio.file.Files.writeString(source.resolve("config/commands/formal.json"),"[]");
        var fixture=new ProcurementInventoryReceiptRealDbIT();fixture.json=new com.fasterxml.jackson.databind.ObjectMapper();
        fixture.procurementDirectory=source.toString();
        try {
            for(int index=0;index<4;index++) {
                fixture.caseEvidence=java.nio.file.Files.createDirectory(root.resolve("case-"+index));
                var proof=fixture.prepareReceiptDirectory();
                assertEquals(source.toRealPath().toString(),proof.get("sourceDirectory"));
                var field=fixture.json.readTree(java.nio.file.Path.of(fixture.procurementDirectory).resolve("config/fields/contact.json").toFile());
                assertEquals(2,field.path("allowedWriterCommands").size());
                assertEquals(original,java.nio.file.Files.readString(source.resolve("config/fields/contact.json")));
                assertFalse(java.nio.file.Files.exists(source.resolve("config/commands/source-ui-native-initialization.json")));
                fixture.restoreProductionDirectory();
                assertEquals(source.toRealPath().toString(),fixture.procurementDirectory);
            }
        } finally {
            try(var files=java.nio.file.Files.walk(root)) {
                for(var file:files.sorted(java.util.Comparator.reverseOrder()).toList())java.nio.file.Files.delete(file);
            }
        }
    }
    @Test void actualAopRejectsJointSaveAndBothCounterEntrypointsBeforeTargetInvocation() {
        var target=mock(DynamicDataService.class);
        var observation=new ProcurementInventoryReceiptRealDbIT.ReceiptReadOnlyObservation();
        var factory=new AspectJProxyFactory(target);factory.addAspect(observation);
        DynamicDataService proxy=factory.getProxy();
        var request=new com.auraboot.framework.meta.dto.JointSubTableSaveRequest();
        observation.enter();
        try {
            assertThrows(AssertionError.class,()->proxy.saveWithRelations("pr_purchase_order",request));
            verify(target,never()).saveWithRelations(any(),any());
            assertThrows(AssertionError.class,()->proxy.incrementWithinCap("pr_purchase_order_line","owned","pr_pol_received_qty",1L,"pr_pol_qty"));
            verify(target,never()).incrementWithinCap(any(),any(),any(),anyLong(),any());
            assertThrows(AssertionError.class,()->proxy.increment("pr_purchase_order_line","owned","pr_pol_received_qty",1L));
            verify(target,never()).increment(any(),any(),any(),anyLong());
        } finally {observation.leave();}
    }
    @Test void actualAopBlocksAllSyntheticMutationsButKeepsReadonlyAndOrdinaryPublicCalls() {
        var target=mock(DynamicDataService.class);var observation=new ProcurementInventoryReceiptRealDbIT.ReceiptReadOnlyObservation();
        var factory=new AspectJProxyFactory(target);factory.addAspect(observation);
        DynamicDataService proxy=factory.getProxy();
        when(target.getById("pr_purchase_order","owned")).thenReturn(Map.of("pid","owned"));
        observation.enter();
        try {
            assertEquals(Map.of("pid","owned"),proxy.getById("pr_purchase_order","owned"));
            assertThrows(AssertionError.class,()->proxy.create("pr_purchase_order",Map.of()));
            assertThrows(AssertionError.class,()->proxy.update("pr_purchase_order","owned",Map.of()));
            assertThrows(AssertionError.class,()->proxy.compareAndSet("pr_purchase_order_line","owned","row_version",1L,Map.of("pr_pol_received_qty","0")));
            assertThrows(AssertionError.class,()->proxy.bulkCreate("pr_purchase_order",List.of()));
            assertThrows(AssertionError.class,()->proxy.delete("pr_purchase_order","owned"));
            observation.enter();observation.leave();
            assertThrows(AssertionError.class,()->proxy.batchDelete("pr_purchase_order",List.of("owned")));
        } finally {observation.leave();}
        verify(target,never()).create(any(),any());verify(target,never()).compareAndSet(any(),any(),any(),any(),any());
        proxy.create("pr_purchase_order",Map.of());verify(target).create("pr_purchase_order",Map.of());
    }
}
