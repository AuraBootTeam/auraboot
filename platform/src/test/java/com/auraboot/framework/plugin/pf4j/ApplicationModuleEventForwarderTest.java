package com.auraboot.framework.plugin.pf4j;

import com.auraboot.module.meta.event.CommandCompletedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationListener;
import org.springframework.context.support.GenericApplicationContext;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Hermetic unit tests using real Spring parent/child event propagation. */
class ApplicationModuleEventForwarderTest {
    CommandCompletedEvent event() { return new CommandCompletedEvent(1L,"record","model",Map.of(),"create","create"); }
    static class Fixture implements AutoCloseable {
        final GenericApplicationContext host=new GenericApplicationContext();
        final GenericApplicationContext first=new GenericApplicationContext(),second=new GenericApplicationContext();
        Fixture(ApplicationListener<CommandCompletedEvent> a,ApplicationListener<CommandCompletedEvent> b) {
            ApplicationModuleRegistry registry=mock(ApplicationModuleRegistry.class);
            when(registry.childContexts()).thenReturn(List.of(first,second));
            host.addApplicationListener(new ApplicationModuleEventForwarder(registry));host.refresh();
            first.setParent(host);second.setParent(host);first.addApplicationListener(a);second.addApplicationListener(b);first.refresh();second.refresh();
        }
        public void close(){second.close();first.close();host.close();}
    }
    @Test void bothChildrenReceiveEachHostPublicationExactlyOnce() {
        AtomicInteger first=new AtomicInteger(),second=new AtomicInteger();
        try(Fixture f=new Fixture(e->first.incrementAndGet(),e->second.incrementAndGet())) {
            CommandCompletedEvent event=event();f.host.publishEvent(event);
            assertThat(first).hasValue(1);assertThat(second).hasValue(1);
            f.host.publishEvent(event);assertThat(first).hasValue(2);assertThat(second).hasValue(2);
        }
    }
    @Test void failedChildDoesNotRetainGuardForRetryOrLaterEvents() {
        AtomicBoolean fail=new AtomicBoolean(true);AtomicInteger first=new AtomicInteger(),second=new AtomicInteger();
        try(Fixture f=new Fixture(e->{first.incrementAndGet();if(fail.getAndSet(false))throw new IllegalStateException("child failed");},e->second.incrementAndGet())) {
            CommandCompletedEvent event=event();assertThatThrownBy(()->f.host.publishEvent(event)).isInstanceOf(IllegalStateException.class);
            f.host.publishEvent(event);f.host.publishEvent(event());
            assertThat(first).hasValue(3);assertThat(second).hasValue(2);
        }
    }
    @Test void simultaneousPublicationsOfSameInstanceAreIndependent()throws Exception {
        CountDownLatch entered=new CountDownLatch(2);AtomicInteger first=new AtomicInteger(),second=new AtomicInteger();
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try(Fixture f=new Fixture(e->{first.incrementAndGet();entered.countDown();try{if(!entered.await(3,TimeUnit.SECONDS))throw new IllegalStateException("another publication was suppressed");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}},e->second.incrementAndGet())) {
            CommandCompletedEvent event=event();Future<?> a=pool.submit(()->f.host.publishEvent(event));Future<?> b=pool.submit(()->f.host.publishEvent(event));a.get(5,TimeUnit.SECONDS);b.get(5,TimeUnit.SECONDS);
            assertThat(first).hasValue(2);assertThat(second).hasValue(2);
        } finally { pool.shutdownNow(); }
    }
}
