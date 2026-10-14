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
    @Test void prerequisiteActorHasOnlyDeclaredFunctionalAndModelPermissions() {
        var permissions=ProcurementInventoryReceiptRealDbIT.prerequisitePermissions();
        assertEquals(21,permissions.size());assertEquals(21,Set.copyOf(permissions).size());
        assertFalse(permissions.contains("*"));assertFalse(permissions.contains("qo.srm.use"));
        assertTrue(permissions.containsAll(List.of("meta.command.execute","qo.procurement.integrate",
                "qo.procurement.manage","prod.product.manage","model.prod_product.create","model.prod_product.update")));
    }
    @Test void syntheticProductionWriterIdentityIsRejectedBeforeItsEffect() {
        var fixture=new ProcurementInventoryReceiptRealDbIT();
        assertThrows(AssertionError.class,()->fixture.fixture("qo_procurement_task_common:dispatch",()->{
            fail("A private production writer cannot execute");return null;}));
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
