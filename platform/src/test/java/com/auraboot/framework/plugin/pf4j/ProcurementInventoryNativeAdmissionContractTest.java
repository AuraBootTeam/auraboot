package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.meta.service.DynamicDataService;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.boot.test.context.SpringBootTest;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Hermetic prerequisite guards and real AOP invocation, not a Native receipt verdict. */
class ProcurementInventoryNativeAdmissionContractTest {
    @Test void retainedHappyBuyerHasOnlyTheOriginalP14ReadRoleAndExplicitCredential() {
        var codes=ProcurementInventoryReceiptRealDbIT.receiptReadBuyerPermissions();
        assertEquals(12,codes.size());assertEquals(12,Set.copyOf(codes).size());
        assertEquals(Set.of("qo.procurement.manage","meta.command.execute","page.page.read",
                "model.s2p_sourcing_event.read","model.qo_procurement_execution_common.read",
                "model.pr_supplier_common.read","model.pr_purchase_order.read","model.pr_purchase_order_line.read",
                "model.qo_procurement_shipment_common.read","model.qo_procurement_shipment_line_common.read",
                "model.pr_purchase_request.read","model.pr_spend_category.read"),Set.copyOf(codes));
        assertThrows(AssertionError.class,()->ProcurementInventoryReceiptRealDbIT.receiptReadBuyerPassword(Map.of()));
        assertThrows(AssertionError.class,()->ProcurementInventoryReceiptRealDbIT.receiptReadBuyerPassword(
                Map.of("PROCUREMENT_INVENTORY_BUYER_PASSWORD","short")));
        assertDoesNotThrow(()->ProcurementInventoryReceiptRealDbIT.receiptReadBuyerPassword(
                Map.of("PROCUREMENT_INVENTORY_BUYER_PASSWORD","Synthetic-only-contract-value-123!")));
    }
    @Test void counterAuditRequiresSystemZeroAndRetainsEveryBusinessField() {
        var before=new java.util.LinkedHashMap<String,Object>(Map.of(
                "pid","line", "pr_pol_order_id","order", "pr_pol_qty","300.000001", "pr_pol_received_qty","0",
                "row_version",1L, "updated_by",77L, "updated_at","2026-10-15 07:00:00", "created_by",77L));
        var after=new java.util.LinkedHashMap<>(before);
        after.put("pr_pol_received_qty","250.000000000000"); after.put("row_version",2L);
        after.put("updated_by",0L); after.put("updated_at","2026-10-15 07:00:01");
        ProcurementInventoryReceiptRealDbIT.assertReceiptCounterLineRetained(before,after,"250",1);
        for(Object invalid:List.of(77L,1L,"0",new java.math.BigDecimal("0.1"))) {
            var bad=new java.util.LinkedHashMap<>(after); bad.put("updated_by",invalid);
            assertThrows(AssertionError.class,()->ProcurementInventoryReceiptRealDbIT.assertReceiptCounterLineRetained(before,bad,"250",1));
        }
        for(var change:List.of(Map.entry("pr_pol_qty",(Object)"999"),Map.entry("pr_pol_order_id",(Object)"foreign"),
                Map.entry("created_by",(Object)0L),Map.entry("row_version",(Object)3L),
                Map.entry("updated_at",(Object)"2026-10-15 06:59:59"),Map.entry("pr_pol_received_qty",(Object)"251"))) {
            var bad=new java.util.LinkedHashMap<>(after);bad.put(change.getKey(),change.getValue());
            assertThrows(AssertionError.class,()->ProcurementInventoryReceiptRealDbIT.assertReceiptCounterLineRetained(before,bad,"250",1));
        }
    }

    @Test void competitorAppendixChangesOnlyTwoFixedUpdateListsAndNeverOriginalJson() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var original=json.createArrayNode();
        for(String model:List.of("pr_purchase_order","pr_purchase_order_line","other_model")) {
            var row=original.addObject();row.put("code",model);row.put("owner","same-owner");
            var commands=row.putObject("extension").putObject("recordCommandWriters").putObject("commands");
            commands.putArray("create").add("original:create");commands.putArray("update").add("original:save");
            commands.putArray("delete").add("original:delete");
        }
        String bytesBefore=json.writeValueAsString(original);
        var changed=ProcurementInventoryReceiptRealDbIT.appendReceiptCompetitorWriters(original);
        assertEquals(bytesBefore,json.writeValueAsString(original));
        assertEquals(original.get(2),changed.get(2));
        for(int index=0;index<2;index++) {
            assertEquals(List.of("original:save","pr:fixture_compete_receipt_line"),
                    json.convertValue(changed.get(index).path("extension").path("recordCommandWriters").path("commands").path("update"),List.class));
            for(String operation:List.of("create","delete"))assertEquals(
                    original.get(index).path("extension").path("recordCommandWriters").path("commands").path(operation),
                    changed.get(index).path("extension").path("recordCommandWriters").path("commands").path(operation));
        }
        assertThrows(AssertionError.class,()->ProcurementInventoryReceiptRealDbIT.appendReceiptCompetitorWriters(changed));
        var missing=original.deepCopy();missing.remove(1);
        assertThrows(AssertionError.class,()->ProcurementInventoryReceiptRealDbIT.appendReceiptCompetitorWriters(missing));
        var duplicate=original.deepCopy();duplicate.add(original.get(0).deepCopy());
        assertThrows(AssertionError.class,()->ProcurementInventoryReceiptRealDbIT.appendReceiptCompetitorWriters(duplicate));
    }

    @Test void actualSpringScanExcludesUnrelatedPilotAndRetainsProductionComponents() throws Exception {
        var old=new org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider(true);
        var ctor=Class.forName("org.springframework.boot.test.context.filter.TestTypeExcludeFilter").getDeclaredConstructor();ctor.setAccessible(true);
        var actual=(org.springframework.boot.context.TypeExcludeFilter)ctor.newInstance();
        var factory=new org.springframework.beans.factory.support.DefaultListableBeanFactory();factory.registerSingleton("actualTestTypeExcludeFilter",actual);
        var app=ProcurementInventoryReceiptRealDbIT.InventoryApplication.class;
        var fixed=new org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider(true);
        for(var declaration:app.getAnnotation(org.springframework.context.annotation.ComponentScan.class).excludeFilters())for(var type:declaration.classes()){
            if(declaration.type()==org.springframework.context.annotation.FilterType.CUSTOM){
                var filter=(org.springframework.core.type.filter.TypeFilter)type.getConstructor().newInstance();
                if(filter instanceof org.springframework.beans.factory.BeanFactoryAware aware)aware.setBeanFactory(factory);
                fixed.addExcludeFilter(filter);
            } else if(declaration.type()==org.springframework.context.annotation.FilterType.ANNOTATION){
                fixed.addExcludeFilter(new org.springframework.core.type.filter.AnnotationTypeFilter(type.asSubclass(java.lang.annotation.Annotation.class)));
            } else {assertEquals(org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE,declaration.type());fixed.addExcludeFilter(new org.springframework.core.type.filter.AssignableTypeFilter(type));}
        }
        String pilot="com.auraboot.framework.integration.ReliableIntegrationRuntimeIT$PilotConfiguration",registry="com.auraboot.framework.integration.ReliableEventConsumerRegistry";
        assertTrue(old.findCandidateComponents("com.auraboot.framework.integration").stream().anyMatch(b->pilot.equals(b.getBeanClassName())));
        var clean=fixed.findCandidateComponents("com.auraboot.framework.integration");
        assertFalse(clean.stream().anyMatch(b->pilot.equals(b.getBeanClassName())));assertTrue(clean.stream().anyMatch(b->registry.equals(b.getBeanClassName())));
        assertEquals(app,ProcurementInventoryReceiptRealDbIT.class.getAnnotation(SpringBootTest.class).classes()[0]);
        var declared=app.getAnnotation(org.springframework.context.annotation.ComponentScan.class).excludeFilters();
        assertEquals(List.of(org.springframework.boot.context.TypeExcludeFilter.class,com.auraboot.framework.application.TestApplication.class,org.springframework.boot.SpringBootConfiguration.class),Arrays.stream(declared).flatMap(v->Arrays.stream(v.classes())).toList());
        var canonicalPackages=new java.util.TreeSet<String>();
        for(Class<?> source:List.of(com.auraboot.framework.application.TestApplication.class,com.auraboot.framework.application.MetaApplication.class,
                com.auraboot.framework.application.CoreOnlyApplication.class,com.auraboot.framework.agent.AiRuntimeAutoConfiguration.class))
            canonicalPackages.addAll(Arrays.asList(source.getAnnotation(org.mybatis.spring.annotation.MapperScan.class).value()));
        assertEquals(canonicalPackages,new java.util.TreeSet<>(Arrays.asList(app.getAnnotation(org.mybatis.spring.annotation.MapperScan.class).value())));
        assertEquals(List.of(ProcurementPublicSourceNativeFixture.IsolatedScheduling.class,ProcurementInventoryReceiptRealDbIT.CasObservationConfiguration.class),
                Arrays.asList(ProcurementInventoryReceiptRealDbIT.class.getAnnotation(org.springframework.context.annotation.Import.class).value()));
    }
    @Test void actualConfigurationParseRegistersOnlyOneInventoryMapperWithoutBeanOverrideOrRefresh() throws Exception {
        var ctor=Class.forName("org.springframework.boot.test.context.filter.TestTypeExcludeFilter").getDeclaredConstructor();ctor.setAccessible(true);
        // MyBatis asks only for infrastructure property configurers in its registration phase.
        // Delegate that exact query to the real BeanFactory without starting an application context.
        try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext(){
            @Override public <T> Map<String,T> getBeansOfType(Class<T> type,boolean includeNonSingletons,boolean allowEagerInit){
                assertEquals(org.springframework.beans.factory.config.PropertyResourceConfigurer.class,type);assertFalse(allowEagerInit);
                return getDefaultListableBeanFactory().getBeansOfType(type,includeNonSingletons,allowEagerInit);
            }
        }){
            var factory=context.getDefaultListableBeanFactory();factory.setAllowBeanDefinitionOverriding(false);
            factory.registerSingleton("actualTestTypeExcludeFilter",ctor.newInstance());
            context.getEnvironment().setActiveProfiles("integration-test");
            context.register(ProcurementInventoryReceiptRealDbIT.InventoryApplication.class,
                    ProcurementPublicSourceNativeFixture.IsolatedScheduling.class,ProcurementInventoryReceiptRealDbIT.CasObservationConfiguration.class);
            var parser=new org.springframework.context.annotation.ConfigurationClassPostProcessor();
            parser.setEnvironment(context.getEnvironment());parser.setResourceLoader(context);parser.setBeanClassLoader(context.getClassLoader());
            parser.processConfigBeanDefinitions(factory);
            assertFalse(factory.isAllowBeanDefinitionOverriding());assertFalse(context.isActive());
            var mapperNames=Arrays.stream(factory.getBeanDefinitionNames()).filter(name->name.contains("MapperScannerRegistrar")).toList();
            System.out.println("Inventory parse mapper registrar roster: "+mapperNames);
            assertEquals(Set.of("com.auraboot.framework.agent.AiRuntimeAutoConfiguration#MapperScannerRegistrar#0",
                    ProcurementInventoryReceiptRealDbIT.InventoryApplication.class.getName()+"#MapperScannerRegistrar#0"),Set.copyOf(mapperNames));
            assertFalse(mapperNames.stream().anyMatch(name->name.contains("TestApplication#MapperScannerRegistrar#")));
            assertFalse(factory.containsBeanDefinition("testApplication"));assertFalse(factory.containsBeanDefinition("pilotInventoryConsumer"));
            assertFalse(factory.containsSingleton("dataSource"));assertFalse(factory.containsSingleton("reliableEventConsumerRegistry"));
            assertTrue(factory.containsBeanDefinition("reliableEventConsumerRegistry"));
            assertTrue(factory.containsBeanDefinition("sourceWriteObservation"));assertTrue(factory.containsBeanDefinition("receiptCasObservation"));
            assertEquals(List.of(com.auraboot.framework.application.TestApplication.class.getPackageName()),
                    org.springframework.boot.autoconfigure.AutoConfigurationPackages.get(factory));
            for(String name:mapperNames){
                var scanner=factory.getBean(name,org.mybatis.spring.mapper.MapperScannerConfigurer.class);scanner.setApplicationContext(context);
                scanner.postProcessBeanDefinitionRegistry(factory);
            }
            assertTrue(factory.containsBeanDefinition("applicationDefinitionMapper"));
            var scan=new org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider(false){
                @Override protected boolean isCandidateComponent(org.springframework.beans.factory.annotation.AnnotatedBeanDefinition definition){
                    return definition.getMetadata().isIndependent() && definition.getMetadata().isInterface();
                }
            };
            scan.addIncludeFilter(new org.springframework.core.type.filter.AnnotationTypeFilter(org.apache.ibatis.annotations.Mapper.class));
            var annotatedMappers=new java.util.TreeSet<String>();
            for(String pkg:List.of("com.auraboot.framework","com.auraboot.module","com.auraboot.smart.framework.engine.persister.database"))
                for(var definition:scan.findCandidateComponents(pkg))annotatedMappers.add(definition.getBeanClassName());
            var registeredMappers=new java.util.TreeSet<String>();
            for(String name:factory.getBeanDefinitionNames()){
                var definition=factory.getBeanDefinition(name);
                if(org.mybatis.spring.mapper.MapperFactoryBean.class.getName().equals(definition.getBeanClassName())){
                    Object mapper=definition.getConstructorArgumentValues().getGenericArgumentValues().getFirst().getValue();
                    registeredMappers.add(mapper instanceof Class<?> type?type.getName():mapper.toString());
                }
            }
            assertFalse(annotatedMappers.isEmpty());assertTrue(registeredMappers.containsAll(annotatedMappers),
                    ()->"Missing annotated production mappers: "+annotatedMappers.stream().filter(mapper->!registeredMappers.contains(mapper)).toList());
            System.out.println("Inventory explicit production mapper definitions="+registeredMappers.size()+" annotatedMapperRoster="+annotatedMappers.size());
            assertFalse(context.isActive());assertFalse(factory.containsSingleton("dataSource"));assertFalse(factory.containsSingleton("applicationDefinitionMapper"));
        }
    }
    @Test void exactPluginConsumerOwnershipRejectsPilotForgedInstanceForeignArtifactAndExtraConsumer() throws Exception {
        var parent=com.auraboot.framework.plugin.extension.integration.ReliableEventConsumerExtension.class.getClassLoader();
        Path directory=java.nio.file.Files.createTempDirectory("inventory-consumer-authority-");
        Path inventory=consumerFixtureJar(directory.resolve("inventory"),Map.of(
                "com.auraboot.plugins.inventory.ReliableInventoryEventConsumer",List.of("inventory.public-command-adapter.v1","procurement.purchase-order.receipt-requested.v1"),
                "com.auraboot.plugins.inventory.ReliableReceiptTraceAdapter",List.of("inventory.receipt-trace-adapter.v1","inventory.receipt.confirmed.v1")));
        Path procurement=consumerFixtureJar(directory.resolve("procurement"),Map.of(
                "com.auraboot.plugins.procurement.ReliableProcurementReceiptConsumer",List.of("procurement.confirmed-receipt-counter.v1","inventory.receipt.confirmed.v1")));
        try(var invLoader=new java.net.URLClassLoader(new java.net.URL[]{inventory.toUri().toURL()},parent);
            var prLoader=new java.net.URLClassLoader(new java.net.URL[]{procurement.toUri().toURL()},parent)){
            var type=com.auraboot.framework.plugin.extension.integration.ReliableEventConsumerExtension.class;
            var intake=type.cast(invLoader.loadClass("com.auraboot.plugins.inventory.ReliableInventoryEventConsumer").getConstructor().newInstance());
            var trace=type.cast(invLoader.loadClass("com.auraboot.plugins.inventory.ReliableReceiptTraceAdapter").getConstructor().newInstance());
            var counter=type.cast(prLoader.loadClass("com.auraboot.plugins.procurement.ReliableProcurementReceiptConsumer").getConstructor().newInstance());
            var manager=mock(AuraPluginManager.class);var json=new com.fasterxml.jackson.databind.ObjectMapper();var admitted=json.createObjectNode();
            for(var owner:Map.of("com.auraboot.inventory",inventory,"com.auraboot.procurement",procurement).entrySet()){
                String digest=ProcurementPublicSourceNativeFixture.sha(owner.getValue());admitted.putObject(owner.getKey()).put("path",owner.getValue().toString()).put("sha256",digest);
                var wrapper=mock(org.pf4j.PluginWrapper.class);when(wrapper.getPluginState()).thenReturn(org.pf4j.PluginState.STARTED);when(wrapper.getPluginPath()).thenReturn(owner.getValue());
                when(wrapper.getPluginClassLoader()).thenReturn(owner.getKey().equals("com.auraboot.inventory")?invLoader:prLoader);when(manager.getPlugin(owner.getKey())).thenReturn(wrapper);
            }
            for(var consumer:List.of(intake,trace,counter)){
                boolean isCounter=consumer==counter;Path jar=isCounter?procurement:inventory;String owner=isCounter?"com.auraboot.procurement":"com.auraboot.inventory";
                when(manager.observeLoadedArtifact(consumer.getClass())).thenReturn(java.util.Optional.of(new AuraPluginManager.LoadedPluginArtifact(owner,jar,"sha256:"+ProcurementPublicSourceNativeFixture.sha(jar))));
                when(manager.isDiscoveredExtensionInstance(type,consumer)).thenReturn(consumer!=trace);
            }
            var discovered=List.of(intake,trace,counter);String request="procurement.purchase-order.receipt-requested.v1",posted="inventory.receipt.confirmed.v1";
            ProcurementInventoryReceiptRealDbIT.assertConsumerOwnership(request,List.of(intake),discovered,manager,admitted);
            ProcurementInventoryReceiptRealDbIT.assertConsumerOwnership(posted,List.of(trace,counter),discovered,manager,admitted);
            String physical=ProcurementPublicSourceNativeFixture.sha(inventory);
            for(String malformed:List.of(physical,"SHA256:"+physical,"sha256:"+physical.substring(1),"sha256:"+"0".repeat(64))){
                when(manager.observeLoadedArtifact(intake.getClass())).thenReturn(java.util.Optional.of(new AuraPluginManager.LoadedPluginArtifact("com.auraboot.inventory",inventory,malformed)));
                assertThrows(AssertionError.class,()->ProcurementInventoryReceiptRealDbIT.assertConsumerOwnership(request,List.of(intake),discovered,manager,admitted));
            }
            when(manager.observeLoadedArtifact(intake.getClass())).thenReturn(java.util.Optional.of(new AuraPluginManager.LoadedPluginArtifact("com.auraboot.inventory",inventory,"sha256:"+physical)));
            ((com.fasterxml.jackson.databind.node.ObjectNode)admitted.path("com.auraboot.inventory")).put("sha256","sha256:"+physical);
            assertThrows(AssertionError.class,()->ProcurementInventoryReceiptRealDbIT.assertConsumerOwnership(request,List.of(intake),discovered,manager,admitted));
            ((com.fasterxml.jackson.databind.node.ObjectNode)admitted.path("com.auraboot.inventory")).put("sha256",physical);
            var forged=type.cast(intake.getClass().getConstructor().newInstance());
            assertThrows(AssertionError.class,()->ProcurementInventoryReceiptRealDbIT.assertConsumerOwnership(request,List.of(forged),discovered,manager,admitted));
            var forgedTrace=type.cast(trace.getClass().getConstructor().newInstance());
            assertThrows(AssertionError.class,()->ProcurementInventoryReceiptRealDbIT.assertConsumerOwnership(posted,List.of(forgedTrace,counter),discovered,manager,admitted));
            var pilot=new com.auraboot.framework.plugin.extension.integration.ReliableEventConsumerExtension(){public String consumerCode(){return "inventory.receive-source-receipt.v1";}public java.util.Set<String> subscribedEventTypes(){return java.util.Set.of(request);}public void consume(com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope e){fail("Pilot must not be invoked");}};
            assertThrows(AssertionError.class,()->ProcurementInventoryReceiptRealDbIT.assertConsumerOwnership(request,List.of(intake,pilot),discovered,manager,admitted));
            assertThrows(AssertionError.class,()->ProcurementInventoryReceiptRealDbIT.assertConsumerOwnership(posted,List.of(trace,counter,pilot),discovered,manager,admitted));
            when(manager.isDiscoveredExtensionInstance(type,intake)).thenReturn(false);
            assertThrows(AssertionError.class,()->ProcurementInventoryReceiptRealDbIT.assertConsumerOwnership(request,List.of(intake),discovered,manager,admitted));
            when(manager.isDiscoveredExtensionInstance(type,intake)).thenReturn(true);
            when(manager.observeLoadedArtifact(trace.getClass())).thenReturn(java.util.Optional.of(new AuraPluginManager.LoadedPluginArtifact("foreign.plugin",inventory,"sha256:"+ProcurementPublicSourceNativeFixture.sha(inventory))));
            assertThrows(AssertionError.class,()->ProcurementInventoryReceiptRealDbIT.assertConsumerOwnership(posted,List.of(trace,counter),discovered,manager,admitted));
            when(manager.observeLoadedArtifact(trace.getClass())).thenReturn(java.util.Optional.of(new AuraPluginManager.LoadedPluginArtifact("com.auraboot.inventory",procurement,"sha256:"+ProcurementPublicSourceNativeFixture.sha(procurement))));
            assertThrows(AssertionError.class,()->ProcurementInventoryReceiptRealDbIT.assertConsumerOwnership(posted,List.of(trace,counter),discovered,manager,admitted));
        } finally {try(var paths=java.nio.file.Files.walk(directory)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())java.nio.file.Files.delete(path);}}
    }
    public static class FreshDiscoveryConsumer implements com.auraboot.framework.plugin.extension.integration.ReliableEventConsumerExtension {
        public String consumerCode(){return "observation-only";}
        public java.util.Set<String> subscribedEventTypes(){return java.util.Set.of("observation-event");}
        public void consume(com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope event){fail("Observation cannot consume");}
    }
    static class FreshDiscoveryManager extends AuraPluginManager {
        final org.pf4j.DefaultExtensionFactory factory=new org.pf4j.DefaultExtensionFactory();int calls;
        final java.util.List<Class<?>> lookupTypes=new java.util.ArrayList<>();
        com.auraboot.framework.plugin.extension.integration.ReliableEventConsumerExtension nestedConsumer;
        FreshDiscoveryManager(){super("unused-hermetic-plugin-directory");}
        @Override public <T> List<T> getExtensionsOfType(Class<T> type){
            calls++;lookupTypes.add(type);
            if(type==com.auraboot.framework.plugin.extension.integration.ExternalEventIngressExtension.class)return List.of();
            assertSame(com.auraboot.framework.plugin.extension.integration.ReliableEventConsumerExtension.class,type);
            return List.of(type.cast(nestedConsumer!=null?nestedConsumer:factory.create(FreshDiscoveryConsumer.class)));
        }
    }
    @Test void realRegistryObservationPreservesFirstFactoryInstanceAndDoesNotRediscover(){
        var target=new FreshDiscoveryManager();var observer=new ProcurementInventoryReceiptRealDbIT.ReceiptDiscoveryObservation();
        var proxyFactory=new AspectJProxyFactory(target);proxyFactory.setProxyTargetClass(true);proxyFactory.addAspect(observer);
        AuraPluginManager manager=proxyFactory.getProxy();
        var core=new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        var registry=new com.auraboot.framework.integration.ReliableEventConsumerRegistry(manager,core.getBeanProvider(com.auraboot.framework.plugin.extension.integration.ReliableEventConsumerExtension.class));
        observer.begin();var registered=registry.consumersFor("observation-event");var original=observer.finish();
        assertEquals(1,target.calls);assertEquals(1,registered.size());assertSame(original.getFirst(),registered.getFirst());
        var later=manager.getExtensionsOfType(com.auraboot.framework.plugin.extension.integration.ReliableEventConsumerExtension.class);
        assertNotSame(later.getFirst(),registered.getFirst());assertSame(original.getFirst(),registered.getFirst());
        observer.begin();assertThrows(AssertionError.class,observer::finish);
        observer.begin();manager.getExtensionsOfType(com.auraboot.framework.plugin.extension.integration.ReliableEventConsumerExtension.class);manager.getExtensionsOfType(com.auraboot.framework.plugin.extension.integration.ReliableEventConsumerExtension.class);
        assertThrows(AssertionError.class,observer::finish);
    }
    @Test void registryDiscoveryIgnoresNestedIngressLookupsButStillRequiresOneReliableDiscovery() {
        var managerRef=new java.util.concurrent.atomic.AtomicReference<AuraPluginManager>();
        var consumer=new com.auraboot.framework.plugin.extension.integration.ReliableEventConsumerExtension() {
            public String consumerCode(){return "nested-observation-consumer";}
            public java.util.Set<String> subscribedEventTypes(){
                managerRef.get().getExtensionsOfType(com.auraboot.framework.plugin.extension.integration.ExternalEventIngressExtension.class);
                return java.util.Set.of("nested-observation-event");
            }
            public void consume(com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope event){fail("Discovery cannot consume");}
        };
        var target=new FreshDiscoveryManager();target.nestedConsumer=consumer;var lookupTypes=target.lookupTypes;
        var observer=new ProcurementInventoryReceiptRealDbIT.ReceiptDiscoveryObservation();
        var proxyFactory=new AspectJProxyFactory(target);proxyFactory.setProxyTargetClass(true);proxyFactory.addAspect(observer);
        AuraPluginManager manager=proxyFactory.getProxy();managerRef.set(manager);
        var core=new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        var registry=new com.auraboot.framework.integration.ReliableEventConsumerRegistry(manager,core.getBeanProvider(com.auraboot.framework.plugin.extension.integration.ReliableEventConsumerExtension.class));
        observer.begin();var registered=registry.consumersFor("nested-observation-event");var original=observer.finish();
        assertEquals(List.of(com.auraboot.framework.plugin.extension.integration.ReliableEventConsumerExtension.class,
                com.auraboot.framework.plugin.extension.integration.ExternalEventIngressExtension.class),lookupTypes);
        assertEquals(1,registered.size());assertSame(consumer,registered.getFirst());assertSame(consumer,original.getFirst());
        observer.begin();manager.getExtensionsOfType(com.auraboot.framework.plugin.extension.integration.ExternalEventIngressExtension.class);
        assertThrows(AssertionError.class,observer::finish);
    }

    private static Path consumerFixtureJar(Path root,Map<String,List<String>> definitions) throws Exception {
        java.nio.file.Files.createDirectories(root);var sources=new java.util.ArrayList<String>();
        for(var entry:definitions.entrySet()){
            String name=entry.getKey(),simple=name.substring(name.lastIndexOf('.')+1),pkg=name.substring(0,name.lastIndexOf('.'));
            Path file=root.resolve(simple+".java");
            java.nio.file.Files.writeString(file,"package "+pkg+"; import com.auraboot.framework.plugin.extension.integration.*; import java.util.*; public class "+simple+" implements ReliableEventConsumerExtension {public String consumerCode(){return \""+entry.getValue().get(0)+"\";} public Set<String> subscribedEventTypes(){return Set.of(\""+entry.getValue().get(1)+"\");} public void consume(IntegrationEventEnvelope e){throw new AssertionError(\"Authority fixture cannot consume\");}}");sources.add(file.toString());
        }
        var args=new java.util.ArrayList<String>(List.of("-proc:none","-classpath",System.getProperty("java.class.path"),"-d",root.toString()));args.addAll(sources);
        assertEquals(0,javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new)));
        Path jar=root.resolve("consumer-fixture.jar");try(var stream=new java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(jar));var paths=java.nio.file.Files.walk(root)){
            for(Path path:paths.filter(v->v.toString().endsWith(".class")).toList()){stream.putNextEntry(new java.util.jar.JarEntry(root.relativize(path).toString()));stream.write(java.nio.file.Files.readAllBytes(path));stream.closeEntry();}
        }return jar.toRealPath();
    }
    @Test void offlineAdmissionSealsBothCompleteInventoriesAndRejectsWrongOriginOrChangedBytes() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var app=java.nio.file.Files.createTempDirectory("inventory-offline-");
        try {
            var original=app.resolve("platform/build/classes/java/main");var offline=app.resolve("platform/build/classes-instrumented-main");
            String relative="com/auraboot/framework/common/crypto/FieldEncryptionService.class";
            for(Path root:List.of(original,offline)){java.nio.file.Files.createDirectories(root.resolve(relative).getParent());java.nio.file.Files.writeString(root.resolve(relative),root==original?"original":"instrumented");}
            var gradle=app.resolve("platform/build.gradle");java.nio.file.Files.writeString(gradle,"canonical offline task");
            var proof=json.createObjectNode().put("mode","jacoco-offline").put("gradlePath",gradle.toRealPath().toString()).put("gradleSha256",ProcurementPublicSourceNativeFixture.sha(gradle)).put("originalRoot",original.toRealPath().toString()).put("instrumentedRoot",offline.toRealPath().toString());
            proof.set("originalClassesSha256",json.valueToTree(ProcurementInventoryNativeAdmission.productionClassInventory(original)));
            proof.set("instrumentedClassesSha256",json.valueToTree(ProcurementInventoryNativeAdmission.productionClassInventory(offline)));
            var resource=offline.resolve(relative);var resources=List.of(resource);
            ProcurementInventoryNativeAdmission.validateProductionClasses(proof,app,offline,true,resources,json);
            assertThrows(AssertionError.class,()->ProcurementInventoryNativeAdmission.validateProductionClasses(proof,app,original,true,resources,json));
            assertThrows(AssertionError.class,()->ProcurementInventoryNativeAdmission.validateProductionClasses(proof,app,offline,false,resources,json));
            assertThrows(AssertionError.class,()->ProcurementInventoryNativeAdmission.validateProductionClasses(proof,app,offline,true,List.of(resource,resource),json));
            var badMode=proof.deepCopy().put("mode","uncovered");assertThrows(AssertionError.class,()->ProcurementInventoryNativeAdmission.validateProductionClasses(badMode,app,offline,true,resources,json));
            var wrongRoot=proof.deepCopy().put("originalRoot",offline.toString());
            assertThrows(AssertionError.class,()->ProcurementInventoryNativeAdmission.validateProductionClasses(wrongRoot,app,offline,true,resources,json));
            java.nio.file.Files.writeString(gradle,"changed coverage configuration");assertThrows(AssertionError.class,()->ProcurementInventoryNativeAdmission.validateProductionClasses(proof,app,offline,true,resources,json));
            java.nio.file.Files.writeString(gradle,"canonical offline task");
            java.nio.file.Files.writeString(resource,"changed class");assertThrows(AssertionError.class,()->ProcurementInventoryNativeAdmission.validateProductionClasses(proof,app,offline,true,resources,json));
            java.nio.file.Files.writeString(resource,"instrumented");java.nio.file.Files.writeString(offline.resolve("Extra.class"),"extra");
            assertThrows(AssertionError.class,()->ProcurementInventoryNativeAdmission.validateProductionClasses(proof,app,offline,true,resources,json));
            java.nio.file.Files.delete(offline.resolve("Extra.class"));java.nio.file.Files.delete(resource);
            assertThrows(AssertionError.class,()->ProcurementInventoryNativeAdmission.validateProductionClasses(proof,app,offline,true,resources,json));
        } finally {try(var paths=java.nio.file.Files.walk(app)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())java.nio.file.Files.delete(path);}}
    }
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
    @Test void receiptResponseDiagnosticsPreserveSafeBusinessClassificationWithoutRawContext() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var response=json.readTree("{\"code\":\"35000\",\"context\":{\"exception\":\"BusinessException\",\"messageKey\":\"$i18n:command.handler.failed\",\"detail\":\"secret SELECT * from private_table FIELD_WRITER_DENIED pid=hidden\",\"cause\":\"token=private\"},\"data\":{\"phaseReached\":\"handler\"}}");
        var actual=ProcurementInventoryReceiptRealDbIT.safeReceiptResponseDiagnostics(response);
        assertEquals("$i18n:command.handler.failed",actual.get("messageKey"));
        assertEquals("BusinessException",actual.get("exceptionType"));assertEquals("handler",actual.get("phaseReached"));
        assertEquals(List.of("FIELD_WRITER_DENIED"),actual.get("authoritySignals"));
        String saved=json.writeValueAsString(actual);
        for(String unsafe:List.of("secret","SELECT","private_table","hidden","token=","cause","detail"))assertFalse(saved.contains(unsafe),unsafe);
        var opaque=ProcurementInventoryReceiptRealDbIT.safeReceiptResponseDiagnostics(json.readTree("{\"context\":{\"messageKey\":\"Plugin handler execution failed: Managed purchase-order line unit is required\",\"exception\":\"privateIdentity\"},\"data\":{\"phaseReached\":\"secretPhase\"}}"));
        assertEquals("MANAGED_UNIT_REQUIRED",opaque.get("businessGuard"));
        assertFalse(opaque.containsKey("messageKey"));assertFalse(opaque.containsKey("exceptionType"));assertFalse(opaque.containsKey("phaseReached"));
        var badKey=ProcurementInventoryReceiptRealDbIT.safeReceiptResponseDiagnostics(json.readTree("{\"context\":{\"messageKey\":\"$i18n:good\\nsecret\",\"detail\":\"NOT_FIELD_WRITER_DENIED_SUFFIX\"}}"));
        assertFalse(badKey.containsKey("messageKey"));assertEquals(List.of(),badKey.get("authoritySignals"));
        assertEquals(false,actual.get("rawResponseOrCredentialsPersisted"));
    }
    @Test void independentApplicationAndRuntimeRequireBothExactIdentitiesAndUnchangedMain() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();String app="a".repeat(40),runtime="b".repeat(40);
        var input=json.createObjectNode().put("applicationCoreCommit",app).put("runtimeCoreCommit",runtime);
        var sources=input.putObject("sourceSet");for(String key:List.of("oss","quote","enterprise","crm","plugins"))sources.put(key,runtime);
        ProcurementInventoryNativeAdmission.validateApplicationIdentity(input,app,"","");
        assertThrows(AssertionError.class,()->ProcurementInventoryNativeAdmission.validateApplicationIdentity(input,runtime,"",""));
        assertThrows(AssertionError.class,()->ProcurementInventoryNativeAdmission.validateApplicationIdentity(input,app,"M file",""));
        assertThrows(AssertionError.class,()->ProcurementInventoryNativeAdmission.validateApplicationIdentity(input,app,"","Main changed"));
        sources.put("oss",app);assertThrows(AssertionError.class,()->ProcurementInventoryNativeAdmission.validateApplicationIdentity(input,app,"",""));
    }
    @Test void completeSixClassFamiliesRejectMissingDependencyAndTrackInnerBytes() throws Exception {
        var directory=java.nio.file.Files.createTempDirectory("inventory-six-family-");
        try {
            for(String source:ProcurementInventoryNativeAdmission.SOURCES){var file=directory.resolve(source.replace("platform/src/test/java/","").replace(".java",".class"));java.nio.file.Files.createDirectories(file.getParent());java.nio.file.Files.writeString(file,"fixture bytes");}
            var first=ProcurementInventoryNativeAdmission.classInventory(directory);assertEquals(7,first.size());
            Path dependency=directory.resolve("com/auraboot/framework/plugin/pf4j/ProcurementSourceUiFixtureProducerIT.class");
            java.nio.file.Files.writeString(dependency,"changed dependency");assertNotEquals(first,ProcurementInventoryNativeAdmission.classInventory(directory));
            var inner=dependency.resolveSibling("ProcurementSourceUiFixtureProducerIT$Inner.class");java.nio.file.Files.writeString(inner,"inner");
            assertEquals(8,ProcurementInventoryNativeAdmission.classInventory(directory).size());
            java.nio.file.Files.delete(dependency);assertThrows(AssertionError.class,()->ProcurementInventoryNativeAdmission.classInventory(directory));
        } finally {try(var files=java.nio.file.Files.walk(directory)){for(var file:files.sorted(java.util.Comparator.reverseOrder()).toList())java.nio.file.Files.delete(file);}}
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
    @Test void exactReplayRetainsOriginalVersionInsideActualPlatformIntent() {
        var original=Map.<String,Object>of("pid","receipt","inv_in_status","draft","row_version",4L);
        var confirmed=Map.<String,Object>of("pid","receipt","inv_in_status","confirmed","row_version",5L);
        var replay=ProcurementInventoryReceiptRealDbIT.originalConfirmIntent(original,confirmed);
        assertEquals(4L,replay.get("row_version"));assertEquals(original,replay);
        var first=actualConfirmIntent(original);assertEquals(first,actualConfirmIntent(replay));
        assertNotEquals(first,actualConfirmIntent(confirmed),"Changing expectedVersion is a different immutable command intent");
        assertThrows(AssertionError.class,()->ProcurementInventoryReceiptRealDbIT.originalConfirmIntent(original,Map.of("pid","other","inv_in_status","confirmed","row_version",5L)));
        assertThrows(AssertionError.class,()->ProcurementInventoryReceiptRealDbIT.originalConfirmIntent(original,Map.of("pid","receipt","inv_in_status","confirmed","row_version",4L)));
    }
    private Map<String,Object> actualConfirmIntent(Map<String,Object> record) {
        var request=new com.auraboot.framework.meta.dto.CommandExecuteRequest();request.setTargetRecordPid("receipt");
        request.setExpectedVersion(((Number)record.get("row_version")).intValue());request.setClientRequestId("same-key");request.setPayload(Map.of());
        var context=com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext.builder()
                .commandCode("inv:confirm_warehouse_in").request(request).tenantId(22L).userId(33L).payload(Map.of()).build();
        return com.auraboot.framework.meta.service.impl.pipeline.CommandIdempotencyIntent.snapshot(context);
    }
    @Test void publicOutcomeRequiresExactCodeAndRealCompletedReplayFlag() {
        var success=new ProcurementInventoryReceiptRealDbIT.ReceiptOutcome(200,"0",true,"completed");
        ProcurementInventoryReceiptRealDbIT.assertReceiptOutcome(success,200,"0",true);
        for(var invalid:List.of(new ProcurementInventoryReceiptRealDbIT.ReceiptOutcome(200,"0",false,"completed"),
                new ProcurementInventoryReceiptRealDbIT.ReceiptOutcome(200,"200",true,"completed"),
                new ProcurementInventoryReceiptRealDbIT.ReceiptOutcome(200,"0",true,"handler")))
            assertThrows(AssertionError.class,()->ProcurementInventoryReceiptRealDbIT.assertReceiptOutcome(invalid,200,"0",true));
        ProcurementInventoryReceiptRealDbIT.assertReceiptOutcome(new ProcurementInventoryReceiptRealDbIT.ReceiptOutcome(409,"41001",false,""),409,"41001",false);
        assertThrows(AssertionError.class,()->ProcurementInventoryReceiptRealDbIT.assertReceiptOutcome(new ProcurementInventoryReceiptRealDbIT.ReceiptOutcome(409,"41002",false,""),409,"41001",false));
    }
    @Test void originalTargetVersionMapperIsObservedWithoutSubstitutionAndWrongScopeIsRejected() {
        var mapper=mock(com.auraboot.framework.meta.mapper.DynamicDataMapper.class);
        var rows=List.<Map<String,Object>>of();when(mapper.selectTargetVersionForUpdate(anyString(),anyString(),anyLong(),anyString())).thenReturn(rows);
        var observer=new ProcurementInventoryReceiptRealDbIT.ReceiptTargetVersionObservation();
        var factory=new AspectJProxyFactory(mapper);factory.addAspect(observer);
        var proxy=(com.auraboot.framework.meta.mapper.DynamicDataMapper)factory.getProxy();
        com.auraboot.framework.application.tenant.MetaContext.setContext(22L,33L,"user","native-test");
        try {
            observer.begin(22L,"trusted_receipt","pid","foreign-pid");
            assertSame(rows,proxy.selectTargetVersionForUpdate("trusted_receipt","pid",22L,"foreign-pid"));
            assertEquals(0,observer.finish().get("rowCount"));verify(mapper).selectTargetVersionForUpdate("trusted_receipt","pid",22L,"foreign-pid");
            for(String mismatch:List.of("tenant","table","pid","nonempty","context")) {
                observer.begin(22L,"trusted_receipt","pid","foreign-pid");
                if(mismatch.equals("context"))com.auraboot.framework.application.tenant.MetaContext.setContext(23L,33L,"user","native-test");
                if(mismatch.equals("nonempty"))when(mapper.selectTargetVersionForUpdate(anyString(),anyString(),anyLong(),anyString())).thenReturn(List.of(Map.of("row_version",4L)));
                try {assertThrows(AssertionError.class,()->proxy.selectTargetVersionForUpdate(mismatch.equals("table")?"wrong":"trusted_receipt","pid",mismatch.equals("tenant")?23L:22L,mismatch.equals("pid")?"wrong":"foreign-pid"));}
                finally {observer.clear();com.auraboot.framework.application.tenant.MetaContext.setContext(22L,33L,"user","native-test");when(mapper.selectTargetVersionForUpdate(anyString(),anyString(),anyLong(),anyString())).thenReturn(rows);}
            }
            observer.begin(22L,"trusted_receipt","pid","foreign-pid");assertThrows(AssertionError.class,observer::finish);
        } finally {observer.clear();com.auraboot.framework.application.tenant.MetaContext.clear();}
    }

}
