package com.auraboot.framework.plugin.pf4j;

import com.auraboot.module.meta.event.CommandCompletedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.AnnotationConfigUtils;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.context.support.GenericApplicationContext;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Hermetic unit tests using real Spring parent/child event propagation. */
class ApplicationModuleEventForwarderConcurrencyTest {
    CommandCompletedEvent event() { return new CommandCompletedEvent(1L,"record","model",Map.of(),"create","create"); }
    static class Fixture implements AutoCloseable {
        final GenericApplicationContext host=new GenericApplicationContext();
        final GenericApplicationContext first=new GenericApplicationContext(),second=new GenericApplicationContext();
        Fixture(ApplicationListener<CommandCompletedEvent> a,ApplicationListener<CommandCompletedEvent> b) {
            ApplicationModuleRegistry registry=mock(ApplicationModuleRegistry.class);
            when(registry.childContexts()).thenReturn(List.of(first,second));
            host.addApplicationListener(new ApplicationModuleEventForwarder(registry));host.refresh();
            first.setParent(host);second.setParent(host);first.addApplicationListener(a);second.addApplicationListener(b);
            for(GenericApplicationContext child:List.of(first,second)) {
                AnnotationConfigUtils.registerAnnotationConfigProcessors(child);
                child.registerBean(TransactionalEventListenerFactory.class);
                child.registerBean(CommitListener.class);
                child.refresh();
            }
        }
        public void close(){second.close();first.close();host.close();}
    }
    static class CommitListener {
        final AtomicInteger committed=new AtomicInteger();
        @TransactionalEventListener void completed(CommandCompletedEvent event){committed.incrementAndGet();}
    }
    static class MemoryTransactionManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction(){return new Object();}
        @Override protected void doBegin(Object transaction,TransactionDefinition definition){}
        @Override protected void doCommit(DefaultTransactionStatus status){}
        @Override protected void doRollback(DefaultTransactionStatus status){}
    }
    @Test void childTransactionalListenersRemainBoundToHostCommitAndRollback() {
        try(Fixture f=new Fixture(e->{},e->{})) {
            CommitListener first=f.first.getBean(CommitListener.class),second=f.second.getBean(CommitListener.class);
            TransactionTemplate tx=new TransactionTemplate(new MemoryTransactionManager());
            tx.executeWithoutResult(status->{f.host.publishEvent(event());assertThat(first.committed).hasValue(0);assertThat(second.committed).hasValue(0);});
            assertThat(first.committed).hasValue(1);assertThat(second.committed).hasValue(1);
            tx.executeWithoutResult(status->{f.host.publishEvent(event());status.setRollbackOnly();});
            assertThat(first.committed).hasValue(1);assertThat(second.committed).hasValue(1);
            f.host.publishEvent(event());assertThat(first.committed).hasValue(1);assertThat(second.committed).hasValue(1);
        }
    }
    @Test void forwardingDoesNotRepeatHostBusinessListeners() {
        AtomicInteger host=new AtomicInteger(),first=new AtomicInteger(),second=new AtomicInteger();
        try(Fixture f=new Fixture(e->first.incrementAndGet(),e->second.incrementAndGet())) {
            f.host.addApplicationListener((ApplicationListener<CommandCompletedEvent>)e->host.incrementAndGet());
            f.host.publishEvent(event());assertThat(host).hasValue(1);assertThat(first).hasValue(1);assertThat(second).hasValue(1);
        }
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
