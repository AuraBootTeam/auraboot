package com.auraboot.framework.infrastructure.mq.kafka;

import com.auraboot.framework.infrastructure.mq.MqProperties;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Hermetic budget and strict lifecycle contracts; actual broker/process timing is separate. */
class KafkaMqProviderBoundsTest {
    @Test void theBoundedConstructorUsesOnlySafeTypedBudgetsAndTheDefaultKeyPartitioner() {
        var observed=new AtomicReference<Properties>();
        try(var construction=mockConstruction(KafkaProducer.class,(producer,context)->
                observed.set((Properties)context.arguments().getFirst()))) {
            var budget=KafkaMqProvider.ProducerTimeouts.within(Duration.ofMillis(2001));
            var provider=new KafkaMqProvider(new MqProperties(),budget);
            Properties config=observed.get(); assertNotNull(config);
            assertEquals(1000L,config.get(ProducerConfig.MAX_BLOCK_MS_CONFIG));
            assertEquals(1001,config.get(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG));
            assertEquals(1001,config.get(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG));
            assertEquals(0,config.get(ProducerConfig.LINGER_MS_CONFIG));
            assertEquals("all",config.get(ProducerConfig.ACKS_CONFIG));
            assertEquals("true",config.get(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG));
            assertNull(config.get(ProducerConfig.PARTITIONER_CLASS_CONFIG));
            assertEquals(1,construction.constructed().size()); provider.close(Duration.ofMillis(100));
        }
    }
    @ParameterizedTest @ValueSource(longs={-1,0,1,60001})
    void invalidTotalBudgetsFailBeforeConstructingAProducer(long milliseconds) {
        try(var construction=mockConstruction(KafkaProducer.class)) {
            assertThrows(IllegalArgumentException.class,()->new KafkaMqProvider(new MqProperties(),
                    KafkaMqProvider.ProducerTimeouts.within(Duration.ofMillis(milliseconds))));
            assertTrue(construction.constructed().isEmpty());
        }
    }
    @Test void fractionalMissingOrContradictoryPhaseBudgetsAreRejected() {
        var one=Duration.ofMillis(1);
        assertThrows(IllegalArgumentException.class,()->KafkaMqProvider.ProducerTimeouts.within(Duration.ofNanos(2_000_001)));
        assertThrows(IllegalArgumentException.class,()->new KafkaMqProvider.ProducerTimeouts(null,one,one));
        assertThrows(IllegalArgumentException.class,()->new KafkaMqProvider.ProducerTimeouts(one,Duration.ofMillis(2),one));
        assertThrows(IllegalArgumentException.class,()->new KafkaMqProvider.ProducerTimeouts(Duration.ofSeconds(40),one,Duration.ofSeconds(40)));
        assertThrows(IllegalArgumentException.class,()->KafkaMqProvider.ProducerTimeouts.within(null));
    }
    @Test void producerCloseFailuresRemainFailuresOnEveryLaterClose() {
        @SuppressWarnings("unchecked") KafkaProducer<String,String> producer=mock(KafkaProducer.class);
        var provider=new KafkaMqProvider(producer,"unused:1","bounded-unit");
        var failure=new IllegalStateException("Exact producer close failure");doThrow(failure).when(producer).close(any(Duration.class));
        assertSame(failure,assertThrows(IllegalStateException.class,()->provider.close(Duration.ofMillis(100))));
        assertSame(failure,assertThrows(IllegalStateException.class,()->provider.close(Duration.ofMillis(100))).getCause());
        assertSame(failure,assertThrows(IllegalStateException.class,provider::destroy).getCause());
        verify(producer,times(1)).close(any(Duration.class));
    }
    @Test void anInterruptedCallerStillCleansUpButCannotReportSuccessfulClose() {
        @SuppressWarnings("unchecked") KafkaProducer<String,String> producer=mock(KafkaProducer.class);
        var provider=new KafkaMqProvider(producer,"unused:1","bounded-unit");
        assertFalse(Thread.currentThread().isInterrupted());
        try {
            Thread.currentThread().interrupt();
            var failure=assertThrows(IllegalStateException.class,()->provider.close(Duration.ofMillis(100)));
            assertInstanceOf(InterruptedException.class,failure.getCause()); assertTrue(Thread.currentThread().isInterrupted());
            verify(producer).close(any(Duration.class));
        } finally { Thread.interrupted(); }
    }
    @Test void wakeupAndProducerFailuresAreBothObservableAfterAllOwnedCleanupIsAttempted() throws Exception {
        @SuppressWarnings("unchecked") KafkaProducer<String,String> producer=mock(KafkaProducer.class);
        @SuppressWarnings("unchecked") Consumer<String,String> consumer=mock(Consumer.class);
        var provider=spy(new KafkaMqProvider(producer,"unused:1","bounded-unit"));
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(consumer.poll(any())).thenAnswer(call->{entered.countDown();assertTrue(release.await(2,TimeUnit.SECONDS));return ConsumerRecords.empty();});
        doReturn(consumer).when(provider).createSubscriptionConsumer(anyString(),anyBoolean());
        var wakeup=new IllegalStateException("Exact wakeup failure");
        doAnswer(call->{release.countDown();throw wakeup;}).when(consumer).wakeup();
        var close=new IllegalStateException("Exact producer failure");doThrow(close).when(producer).close(any(Duration.class));
        var stage=provider.subscribeDurably("bounded-topic","bounded-group",record->fail("No records are delivered"));
        try {
            assertTrue(entered.await(1,TimeUnit.SECONDS));long before=System.nanoTime();
            assertSame(wakeup,assertThrows(IllegalStateException.class,()->provider.close(Duration.ofSeconds(1))));
            assertTrue(System.nanoTime()-before<Duration.ofSeconds(2).toNanos());
            assertEquals(1,wakeup.getSuppressed().length); assertSame(close,wakeup.getSuppressed()[0]);
            verify(producer).close(argThat(duration->!duration.isNegative() && duration.compareTo(Duration.ofSeconds(1))<=0));
        } finally {release.countDown();stage.toCompletableFuture().get(1,TimeUnit.SECONDS);}
        assertEquals(0,provider.activeSubscriptionCount());
    }
    @Test void successfulCloseIsIdempotentAndRejectsLaterSubscriptions() {
        @SuppressWarnings("unchecked") KafkaProducer<String,String> producer=mock(KafkaProducer.class);
        var provider=new KafkaMqProvider(producer,"unused:1","bounded-unit");
        provider.close(Duration.ofMillis(100));provider.destroy();
        verify(producer,times(1)).close(any(Duration.class));
        assertThrows(IllegalStateException.class,()->provider.subscribeDurably("topic","group",record->{}));
    }
    @Test void anInterruptedProducerCloseRestoresTheInterruptFlagAndItsOriginalCause() {
        @SuppressWarnings("unchecked") KafkaProducer<String,String> producer=mock(KafkaProducer.class);
        var provider=new KafkaMqProvider(producer,"unused:1","bounded-unit");
        var error=new org.apache.kafka.common.errors.InterruptException(new InterruptedException("Producer was interrupted"));
        Thread.interrupted(); // Kafka's exception constructor itself marks the constructing thread interrupted.
        doThrow(error).when(producer).close(any(Duration.class));
        try {
            assertSame(error,assertThrows(org.apache.kafka.common.errors.InterruptException.class,
                    ()->provider.close(Duration.ofMillis(100))));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {Thread.interrupted();}
    }
    @Test void aConcurrentCloseCannotWaitLongerThanItsOwnLockAdmissionBudget() throws Exception {
        @SuppressWarnings("unchecked") KafkaProducer<String,String> producer=mock(KafkaProducer.class);
        var provider=new KafkaMqProvider(producer,"unused:1","bounded-unit");
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call->{entered.countDown();assertTrue(release.await(2,TimeUnit.SECONDS));return null;}).when(producer).close(any(Duration.class));
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var first=executor.submit(()->provider.close(Duration.ofSeconds(1)));
            try {
                assertTrue(entered.await(1,TimeUnit.SECONDS));long before=System.nanoTime();
                assertThrows(IllegalStateException.class,()->provider.close(Duration.ofMillis(25)));
                assertTrue(System.nanoTime()-before<Duration.ofSeconds(1).toNanos());
            } finally {release.countDown();first.get(1,TimeUnit.SECONDS);}
        }
        provider.close(Duration.ofMillis(100));verify(producer,times(1)).close(any(Duration.class));
    }
    @Test void aCompletionCallbackCannotClaimItHasStoppedItsOwnProducerThread() {
        @SuppressWarnings("unchecked") KafkaProducer<String,String> producer=mock(KafkaProducer.class);
        var provider=new KafkaMqProvider(producer,"unused:1","bounded-unit");
        var callback=new AtomicReference<org.apache.kafka.clients.producer.Callback>();
        when(producer.send(any(),any())).thenAnswer(call->{callback.set(call.getArgument(1));return new java.util.concurrent.CompletableFuture<>();});
        var sent=provider.sendConfirmed("topic","key","wire",java.util.Map.of());
        var closed=sent.whenComplete((metadata,error)->provider.close(Duration.ofMillis(100)));
        var metadata=new org.apache.kafka.clients.producer.RecordMetadata(new org.apache.kafka.common.TopicPartition("topic",0),0,0,0L,1,1);
        callback.get().onCompletion(metadata,null);
        assertSame(metadata,sent.toCompletableFuture().join());
        assertInstanceOf(IllegalStateException.class,assertThrows(java.util.concurrent.CompletionException.class,
                ()->closed.toCompletableFuture().join()).getCause());
        verify(producer,never()).close(any(Duration.class));
        provider.close(Duration.ofMillis(100));verify(producer).close(any(Duration.class));
    }
    @Test void anUncooperativeSdkCloserCannotBlockTheCallerOrBecomeSuccessfulLater() throws Exception {
        @SuppressWarnings("unchecked") KafkaProducer<String,String> producer=mock(KafkaProducer.class);
        var provider=new KafkaMqProvider(producer,"unused:1","bounded-unit");
        var release=new CountDownLatch(1);var entered=new CountDownLatch(1);var owned=new AtomicReference<Thread>();
        doAnswer(call->{
            owned.set(Thread.currentThread());entered.countDown();
            while(release.getCount()!=0) {
                try {release.await();} catch(InterruptedException ignored) { /* Deliberate non-cooperative SDK fault. */ }
            }
            return null;
        }).when(producer).close(any(Duration.class));
        try {
            long before=System.nanoTime();
            assertThrows(IllegalStateException.class,()->provider.close(Duration.ofMillis(50)));
            assertTrue(System.nanoTime()-before<Duration.ofSeconds(1).toNanos());
            assertTrue(entered.await(1,TimeUnit.SECONDS));
            var status=provider.closeStatus();assertTrue(status.requested());assertTrue(status.producerCloserAlive());
            assertEquals(owned.get().threadId(),status.producerCloserThreadId());assertFalse(status.successful());
        } finally {release.countDown();if(owned.get()!=null)owned.get().join(1000);}
        assertFalse(provider.closeStatus().producerCloserAlive());assertTrue(provider.closeStatus().producerCleanupSucceeded());
        assertFalse(provider.closeStatus().successful(),"A missed deadline cannot be relabeled as a successful close");
        assertThrows(IllegalStateException.class,()->provider.close(Duration.ofMillis(100)));
    }
}
