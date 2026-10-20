package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.ServiceTaskActionExtension;
import com.auraboot.framework.plugin.extension.integration.ReliableEventConsumerExtension;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.support.GenericApplicationContext;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual PF4J/Spring factory and artifact, but synthetic extension code and no domain/Auth/PG. */
class AuraPluginDiscoveredInstanceTest {
    @TempDir Path directory;
    AuraPluginManager plugins;
    GenericApplicationContext context;
    Path jar;
    static final String PRODUCER="com.auraboot.plugins.inventory.handler.ConfirmWarehouseInHandler";
    static final String INTAKE="com.auraboot.plugins.inventory.ReliableInventoryEventConsumer";
    static final String COUNTER="com.auraboot.plugins.procurement.ReliableProcurementReceiptConsumer";
    @BeforeEach void prepare() throws Exception {
        Path sources=directory.resolve("sources"),classes=directory.resolve("classes");Files.createDirectories(sources);Files.createDirectories(classes);
        Files.writeString(sources.resolve("ConfirmWarehouseInHandler.java"),"""
            package com.auraboot.plugins.inventory.handler;
            import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
            public class ConfirmWarehouseInHandler implements CommandHandlerExtension {
              public String getCommandType(){return "inv:confirm_warehouse_in";}
              public Object execute(CommandContext context){return java.util.Map.of();}
              public boolean equals(Object other){return true;}
              public int hashCode(){return 1;}
            }
            """);
        Files.writeString(sources.resolve("ReliableInventoryEventConsumer.java"),consumer("inventory","ReliableInventoryEventConsumer","inventory.public-command-adapter.v1"));
        Files.writeString(sources.resolve("ReliableProcurementReceiptConsumer.java"),consumer("procurement","ReliableProcurementReceiptConsumer","procurement.confirmed-receipt-counter.v1"));
        assertThat(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","-classpath",System.getProperty("java.class.path"),"-d",classes.toString(),
            sources.resolve("ConfirmWarehouseInHandler.java").toString(),sources.resolve("ReliableInventoryEventConsumer.java").toString(),sources.resolve("ReliableProcurementReceiptConsumer.java").toString())).isZero();
        Manifest manifest=new Manifest();var attributes=manifest.getMainAttributes();attributes.put(Attributes.Name.MANIFEST_VERSION,"1.0");
        attributes.putValue("Plugin-Id","com.auraboot.inventory");attributes.putValue("Plugin-Version","1.0.0");attributes.putValue("Plugin-Class","org.pf4j.Plugin");
        Path pluginDir=directory.resolve("plugins");Files.createDirectories(pluginDir);jar=pluginDir.resolve("inventory-fixture.jar");
        try(JarOutputStream output=new JarOutputStream(Files.newOutputStream(jar),manifest)) {
            try(var files=Files.walk(classes)){for(Path file:files.filter(Files::isRegularFile).toList()){
                output.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\','/')));output.write(Files.readAllBytes(file));output.closeEntry();}}
            output.putNextEntry(new JarEntry("META-INF/extensions.idx"));output.write((PRODUCER+"\n"+INTAKE+"\n"+COUNTER+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));output.closeEntry();
        }
        context=new GenericApplicationContext();context.refresh();plugins=new AuraPluginManager(pluginDir.toString());plugins.setApplicationContext(context);
        assertThat(plugins.loadPlugin(jar)).isEqualTo("com.auraboot.inventory");assertThat(plugins.startPlugin("com.auraboot.inventory")).isEqualTo(org.pf4j.PluginState.STARTED);
    }
    @AfterEach void close() { if(plugins!=null){plugins.stopPlugins();plugins.unloadPlugins();}if(context!=null)context.close(); }
    @Test void actualFactoryReturnsDifferentInstancesWhileEarlierRegistryInstanceKeepsProof() {
        var first=plugins.getExtensionsOfType(CommandHandlerExtension.class).getFirst();
        var second=plugins.getExtensionsOfType(CommandHandlerExtension.class).getFirst();
        assertThat(first).isNotSameAs(second);assertThat(first.getClass()).isSameAs(second.getClass());
        assertThat(plugins.isDiscoveredExtensionInstance(CommandHandlerExtension.class,first)).isTrue();
        assertThat(plugins.isDiscoveredExtensionInstance(CommandHandlerExtension.class,second)).isTrue();
    }
    @Test void newSameClassEqualsAliasedObjectAndLookupCannotSelfRegister() throws Exception {
        var registered=plugins.getExtensionsOfType(CommandHandlerExtension.class).getFirst();
        var forged=registered.getClass().getConstructor().newInstance();assertThat(forged.equals(registered)).isTrue();
        assertThat(plugins.isDiscoveredExtensionInstance(CommandHandlerExtension.class,forged)).isFalse();
        assertThat(plugins.isDiscoveredExtensionInstance(CommandHandlerExtension.class,forged)).isFalse();
        assertThat(plugins.isDiscoveredExtensionInstance(CommandHandlerExtension.class,registered)).isTrue();
    }
    @Test void actualDiscoveredIntakeInstancePassesOwnerGateWithoutASecondFactoryAlias() throws Exception {
        var intake=plugins.getExtensionsOfType(ReliableEventConsumerExtension.class).stream()
            .filter(c->c.getClass().getName().equals(INTAKE)).findFirst().orElseThrow();
        assertThat(plugins.getExtensionsOfType(ReliableEventConsumerExtension.class).stream().anyMatch(c->c==intake)).isFalse();
        var event=new com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope("1.0","owned-event",
            "procurement.purchase-order.receipt-requested.v1","procurement","source",java.time.Instant.EPOCH,31,"correlation","causation","order",3,
            Map.of("targetCommand","inv:receive_source_receipt","sourceType","procurement.purchase_receipt"),Map.of());
        var authority=mock(com.auraboot.framework.integration.InventoryOwnerModelAuthority.class);
        var applied=new java.util.concurrent.atomic.AtomicBoolean();
        var gate=com.auraboot.framework.integration.InventoryOwnerCommandScope.class.getDeclaredMethod("consume",
            com.auraboot.framework.plugin.extension.integration.IntegrationEventEnvelope.class,ReliableEventConsumerExtension.class,
            AuraPluginManager.class,com.auraboot.framework.integration.InventoryOwnerModelAuthority.class,Runnable.class);
        gate.setAccessible(true);com.auraboot.framework.application.tenant.MetaContext.setContext(31L,0L,"fixture","fixture");
        try {gate.invoke(null,event,intake,plugins,authority,(Runnable)()->applied.set(true));}
        catch (java.lang.reflect.InvocationTargetException failure) { throw new AssertionError("Actual owner gate rejected discovered instance: " + failure.getCause().getClass().getSimpleName(), failure.getCause()); }
        finally {com.auraboot.framework.application.tenant.MetaContext.clear();}
        assertThat(applied).isTrue();
    }
    @Test void cachedCanonicalCommandRegistryInstanceIsAnActualDiscoveredObject() {
        @SuppressWarnings("unchecked") ObjectProvider<CommandHandlerExtension> core=mock(ObjectProvider.class);when(core.stream()).thenAnswer(call->Stream.empty());
        @SuppressWarnings("unchecked") ObjectProvider<ServiceTaskActionExtension> actions=mock(ObjectProvider.class);
        ExtensionRegistry registry=new ExtensionRegistry(plugins,core,actions);
        try {
            var original=registry.getCommandHandler("inv:confirm_warehouse_in").orElseThrow();
            plugins.getExtensionsOfType(CommandHandlerExtension.class);
            assertThat(registry.getCommandHandler("inv:confirm_warehouse_in").orElseThrow()).isSameAs(original);
            assertThat(plugins.isDiscoveredExtensionInstance(CommandHandlerExtension.class,original)).isTrue();
        } finally {registry.detachLifecycleListener();}
    }
    @Test void actualConsumerDiscoveryUsesExactInterfaceOwnerAndObject() throws Exception {
        var consumers=plugins.getExtensionsOfType(ReliableEventConsumerExtension.class,"com.auraboot.inventory");
        var intake=consumers.stream().filter(c->c.getClass().getName().equals(INTAKE)).findFirst().orElseThrow();
        var wrongOwner=consumers.stream().filter(c->c.getClass().getName().equals(COUNTER)).findFirst().orElseThrow();
        assertThat(plugins.isDiscoveredExtensionInstance(ReliableEventConsumerExtension.class,intake)).isTrue();
        assertThat(plugins.isDiscoveredExtensionInstance(CommandHandlerExtension.class,intake)).isFalse();
        assertThat(plugins.isDiscoveredExtensionInstance(ReliableEventConsumerExtension.class,wrongOwner)).isFalse();
        assertThat(plugins.isDiscoveredExtensionInstance(ReliableEventConsumerExtension.class,intake.getClass().getConstructor().newInstance())).isFalse();
    }
    @Test void stopAndRestartInvalidateOldDiscoveryEvenForSameClassloaderAndBytes() {
        var original=plugins.getExtensionsOfType(CommandHandlerExtension.class).getFirst();plugins.stopPlugin("com.auraboot.inventory");
        assertThat(plugins.isDiscoveredExtensionInstance(CommandHandlerExtension.class,original)).isFalse();plugins.startPlugin("com.auraboot.inventory");
        assertThat(plugins.isDiscoveredExtensionInstance(CommandHandlerExtension.class,original)).isFalse();
        assertThat(plugins.isDiscoveredExtensionInstance(CommandHandlerExtension.class,plugins.getExtensionsOfType(CommandHandlerExtension.class).getFirst())).isTrue();
    }
    @Test void unloadReloadCannotRebindOldImplementationClassOrInstance() {
        var original=plugins.getExtensionsOfType(CommandHandlerExtension.class).getFirst();plugins.stopPlugin("com.auraboot.inventory");plugins.unloadPlugin("com.auraboot.inventory");
        plugins.loadPlugin(jar);plugins.startPlugin("com.auraboot.inventory");var current=plugins.getExtensionsOfType(CommandHandlerExtension.class).getFirst();
        assertThat(original.getClass()).isNotSameAs(current.getClass());
        assertThat(plugins.isDiscoveredExtensionInstance(CommandHandlerExtension.class,original)).isFalse();
        assertThat(plugins.isDiscoveredExtensionInstance(CommandHandlerExtension.class,current)).isTrue();
    }
    @Test void changedPhysicalArtifactRejectsThePreviouslyDiscoveredObject() throws Exception {
        var original=plugins.getExtensionsOfType(CommandHandlerExtension.class).getFirst();byte[] bytes=Files.readAllBytes(jar);
        try {Files.write(jar,new byte[]{1},java.nio.file.StandardOpenOption.APPEND);
            assertThatThrownBy(()->plugins.isDiscoveredExtensionInstance(CommandHandlerExtension.class,original)).isInstanceOf(IllegalStateException.class);
        } finally {Files.write(jar,bytes);}
    }
    private static String consumer(String namespace,String name,String code) {return "package com.auraboot.plugins."+namespace+"; import com.auraboot.framework.plugin.extension.integration.*; import java.util.*; public class "+name+" implements ReliableEventConsumerExtension { public String consumerCode(){return \""+code+"\";} public Set<String> subscribedEventTypes(){return Set.of(\"procurement.purchase-order.receipt-requested.v1\");} public void consume(IntegrationEventEnvelope event){} }";}
}
