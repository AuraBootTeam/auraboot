package com.auraboot.framework.infrastructure.mq.kafka;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Hermetic tests for transport acknowledgement and the durable receipt barrier. */
class KafkaMqReliabilityTest {
    private static final String TOPIC = "observations";
    private static final String GROUP = "durable-receipts";
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 2);
    private KafkaProducer<String, String> producer;
    private Consumer<String, String> consumer;
    private KafkaMqProvider provider;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        producer = mock(KafkaProducer.class);
        consumer = mock(Consumer.class);
        provider = spy(new KafkaMqProvider(producer, "unused:9092", GROUP));
        doReturn(consumer).when(provider).createSubscriptionConsumer(anyString(), anyBoolean());
    }

    @AfterEach
    void tearDown() {
        provider.destroy();
    }

    @Test
    @SuppressWarnings("unchecked")
    void confirmedSendWaitsForCallbackAndPreservesKeyAndHeaders() {
        CompletableFuture<RecordMetadata> result = provider.sendConfirmed(TOPIC, "station:target-7",
                "payload", Map.of("event-id", "event-17")).toCompletableFuture();
        ArgumentCaptor<ProducerRecord<String, String>> record = ArgumentCaptor.forClass(ProducerRecord.class);
        ArgumentCaptor<Callback> callback = ArgumentCaptor.forClass(Callback.class);
        verify(producer).send(record.capture(), callback.capture());
        assertFalse(result.isDone(), "enqueueing locally is not a broker acknowledgement");
        assertEquals("station:target-7", record.getValue().key());
        assertEquals("payload", record.getValue().value());
        assertArrayEquals("event-17".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                record.getValue().headers().lastHeader("event-id").value());
        RecordMetadata metadata = new RecordMetadata(PARTITION, 41, 0, 0L, 0, 0);
        callback.getValue().onCompletion(metadata, null);
        assertSame(metadata, result.join());
        assertEquals(41, result.join().offset());
        assertEquals(2, result.join().partition());
    }

    @Test
    void confirmedSendExposesAsynchronousBrokerFailure() {
        CompletableFuture<RecordMetadata> result = provider.sendConfirmed(TOPIC, "key", "body", null)
                .toCompletableFuture();
        ArgumentCaptor<Callback> callback = ArgumentCaptor.forClass(Callback.class);
        verify(producer).send(any(), callback.capture());
        TimeoutException failure = new TimeoutException("broker acknowledgement timed out");
        callback.getValue().onCompletion(null, failure);
        assertSame(failure, assertThrows(java.util.concurrent.CompletionException.class, result::join).getCause());
    }

    @Test
    void acknowledgementWithoutMetadataIsNotReportedAsSuccess() {
        CompletableFuture<RecordMetadata> result = provider.sendConfirmed(TOPIC, "key", "body", null)
                .toCompletableFuture();
        ArgumentCaptor<Callback> callback = ArgumentCaptor.forClass(Callback.class);
        verify(producer).send(any(), callback.capture());
        callback.getValue().onCompletion(null, null);
        assertInstanceOf(IllegalStateException.class,
                assertThrows(java.util.concurrent.CompletionException.class, result::join).getCause());
    }

    @Test
    void confirmedSendExposesSynchronousFailureAndRejectsMissingKey() {
        IllegalStateException failure = new IllegalStateException("producer is closed");
        when(producer.send(any(), any())).thenThrow(failure);
        CompletableFuture<RecordMetadata> result = provider.sendConfirmed(TOPIC, "key", "body", Map.of())
                .toCompletableFuture();
        assertSame(failure, assertThrows(java.util.concurrent.CompletionException.class, result::join).getCause());
        assertThrows(IllegalArgumentException.class, () -> provider.sendConfirmed(TOPIC, " ", "body", null));
        assertThrows(IllegalArgumentException.class, () -> provider.sendConfirmed(TOPIC, null, "body", null));
        verify(producer, times(1)).send(any(), any());
    }

    @Test
    void durableConsumerDisablesAutoCommitWithoutChangingLegacyMode() {
        assertEquals("false", KafkaMqProvider.consumerProperties("broker:9092", GROUP, true)
                .getProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG));
        assertEquals("true", KafkaMqProvider.consumerProperties("broker:9092", GROUP, false)
                .getProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG));
    }

    @Test
    void commitsOnlyAfterReceiptReturnsAndOnlyItsExactOffset() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch releaseReceipt = new CountDownLatch(1);
        when(consumer.poll(any(Duration.class))).thenReturn(records(41)).thenAnswer(invocation -> {
            provider.unsubscribe(TOPIC, GROUP);
            return ConsumerRecords.empty();
        });
        CompletableFuture<Void> stopped = provider.subscribeDurably(TOPIC, GROUP, record -> {
            assertEquals("target-7", record.key());
            entered.countDown();
            assertTrue(releaseReceipt.await(5, TimeUnit.SECONDS));
        }).toCompletableFuture();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            verify(consumer, never()).commitSync(anyMap());
        } finally {
            releaseReceipt.countDown();
        }
        stopped.get(5, TimeUnit.SECONDS);
        verify(consumer).commitSync(Map.of(PARTITION, new OffsetAndMetadata(42)));
        verify(consumer, never()).commitSync();
        verify(provider).createSubscriptionConsumer(GROUP, true);
    }

    @Test
    void firstReceiptFailureCommitsNothingAndStopsBeforeLaterRecords() throws Exception {
        IOException failure = new IOException("receipt transaction rolled back");
        AtomicInteger calls = new AtomicInteger();
        when(consumer.poll(any(Duration.class))).thenReturn(records(41, 42));
        CompletableFuture<Void> stopped = provider.subscribeDurably(TOPIC, GROUP, record -> {
            calls.incrementAndGet();
            throw failure;
        }).toCompletableFuture();
        assertSame(failure, assertThrows(ExecutionException.class, () -> stopped.get(5, TimeUnit.SECONDS)).getCause());
        assertEquals(1, calls.get());
        verify(consumer, never()).commitSync(anyMap());
        verifyNoInteractions(producer);
        assertFalse(provider.hasSubscription(TOPIC, GROUP));
    }

    @Test
    void handlerErrorCannotBecomeNormalLifecycleCompletion() {
        AssertionError failure = new AssertionError("receipt invariant failed");
        when(consumer.poll(any(Duration.class))).thenReturn(records(41));
        CompletableFuture<Void> stopped = provider.subscribeDurably(TOPIC, GROUP, record -> { throw failure; })
                .toCompletableFuture();
        assertSame(failure, assertThrows(ExecutionException.class, () -> stopped.get(5, TimeUnit.SECONDS)).getCause());
        verify(consumer, never()).commitSync(anyMap());
    }

    @Test
    void secondReceiptFailureCannotCommitPastFirstSuccessfulReceipt() throws Exception {
        when(consumer.poll(any(Duration.class))).thenReturn(records(41, 42, 43));
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<Void> stopped = provider.subscribeDurably(TOPIC, GROUP, record -> {
            calls.incrementAndGet();
            if (record.offset() == 42) {
                throw new IOException("second receipt failed");
            }
        }).toCompletableFuture();
        assertThrows(ExecutionException.class, () -> stopped.get(5, TimeUnit.SECONDS));
        assertEquals(2, calls.get());
        verify(consumer, times(1)).commitSync(Map.of(PARTITION, new OffsetAndMetadata(42)));
        verify(consumer, never()).commitSync();
        verifyNoInteractions(producer);
    }

    @Test
    void commitFailureIsObservableAndStopsLaterDelivery() throws Exception {
        RuntimeException failure = new org.apache.kafka.clients.consumer.CommitFailedException("rebalance");
        doThrow(failure).when(consumer).commitSync(anyMap());
        when(consumer.poll(any(Duration.class))).thenReturn(records(41, 42));
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<Void> stopped = provider.subscribeDurably(TOPIC, GROUP,
                record -> calls.incrementAndGet()).toCompletableFuture();
        assertSame(failure, assertThrows(ExecutionException.class, () -> stopped.get(5, TimeUnit.SECONDS)).getCause());
        assertEquals(1, calls.get());
        verify(consumer, times(1)).commitSync(Map.of(PARTITION, new OffsetAndMetadata(42)));
    }

    @Test
    void unsubscribeDuringReceiptLeavesItReplayableAndRejectsPrematureRestart() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch releaseReceipt = new CountDownLatch(1);
        when(consumer.poll(any(Duration.class))).thenReturn(records(41, 42));
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<Void> stopped = provider.subscribeDurably(TOPIC, GROUP, record -> {
            calls.incrementAndGet();
            entered.countDown();
            assertTrue(releaseReceipt.await(5, TimeUnit.SECONDS));
        }).toCompletableFuture();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class,
                    () -> provider.subscribeDurably(TOPIC, GROUP, record -> fail("duplicate delivery")));
            provider.unsubscribe(TOPIC, GROUP);
            assertThrows(IllegalStateException.class,
                    () -> provider.subscribeDurably(TOPIC, GROUP, record -> fail("premature restart")));
        } finally {
            releaseReceipt.countDown();
        }
        stopped.get(5, TimeUnit.SECONDS);
        assertEquals(1, calls.get());
        verify(consumer, never()).commitSync(anyMap());
        verify(consumer).wakeup();
        verify(consumer).close();
    }

    @Test
    void failedSubscriptionCanRestartInSameGroupAfterClose() throws Exception {
        when(consumer.poll(any(Duration.class))).thenReturn(records(41));
        CompletableFuture<Void> first = provider.subscribeDurably(TOPIC, GROUP,
                record -> { throw new IOException("first receipt failed"); }).toCompletableFuture();
        assertThrows(ExecutionException.class, () -> first.get(5, TimeUnit.SECONDS));
        when(consumer.poll(any(Duration.class))).thenReturn(records(41)).thenAnswer(invocation -> {
            provider.unsubscribe(TOPIC, GROUP);
            return ConsumerRecords.empty();
        });
        AtomicInteger receipts = new AtomicInteger();
        provider.subscribeDurably(TOPIC, GROUP, record -> receipts.incrementAndGet())
                .toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(1, receipts.get());
        verify(consumer).commitSync(Map.of(PARTITION, new OffsetAndMetadata(42)));
        verify(consumer, times(2)).close();
    }

    @Test
    void consumerConstructionFailureIsObservableAndDoesNotLeakRegistration() {
        IllegalStateException failure = new IllegalStateException("invalid configuration");
        doThrow(failure).when(provider).createSubscriptionConsumer(GROUP, true);
        CompletableFuture<Void> stopped = provider.subscribeDurably(TOPIC, GROUP, record -> fail())
                .toCompletableFuture();
        assertSame(failure, assertThrows(ExecutionException.class, () -> stopped.get(5, TimeUnit.SECONDS)).getCause());
        assertEquals(0, provider.activeSubscriptionCount());
    }

    @Test
    void unexpectedWakeupIsObservableAsFailure() {
        org.apache.kafka.common.errors.WakeupException failure = new org.apache.kafka.common.errors.WakeupException();
        when(consumer.poll(any(Duration.class))).thenThrow(failure);
        CompletableFuture<Void> stopped = provider.subscribeDurably(TOPIC, GROUP, record -> fail())
                .toCompletableFuture();
        assertSame(failure, assertThrows(ExecutionException.class, () -> stopped.get(5, TimeUnit.SECONDS)).getCause());
        verify(consumer, never()).commitSync(anyMap());
    }

    @Test
    void consumerCloseFailureIsObservableAfterRequestedStop() {
        IllegalStateException failure = new IllegalStateException("consumer close failed");
        when(consumer.poll(any(Duration.class))).thenAnswer(invocation -> {
            provider.unsubscribe(TOPIC, GROUP);
            return ConsumerRecords.empty();
        });
        doThrow(failure).when(consumer).close();
        CompletableFuture<Void> stopped = provider.subscribeDurably(TOPIC, GROUP, record -> fail())
                .toCompletableFuture();
        assertSame(failure, assertThrows(ExecutionException.class, () -> stopped.get(5, TimeUnit.SECONDS)).getCause());
    }

    @Test
    @SuppressWarnings("unchecked")
    void legacyFailureRetainsRetryTopicKeyAndHeaders() throws Exception {
        ConsumerRecords<String, String> batch = records(41);
        batch.iterator().next().headers().add("event-id", "id-17".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        runFailingLegacyHandler(batch);
        ArgumentCaptor<ProducerRecord<String, String>> retry = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(producer).send(retry.capture(), any());
        assertEquals(TOPIC, retry.getValue().topic());
        assertEquals("target-7", retry.getValue().key());
        assertEquals("event-41", retry.getValue().value());
        assertArrayEquals(new byte[] {'1'}, retry.getValue().headers().lastHeader("x-retry-count").value());
        assertArrayEquals("id-17".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                retry.getValue().headers().lastHeader("event-id").value());
        verify(consumer, never()).commitSync(anyMap());
    }

    @Test
    @SuppressWarnings("unchecked")
    void legacyExhaustedFailureRetainsDeadLetterRouting() throws Exception {
        ConsumerRecords<String, String> batch = records(41);
        batch.iterator().next().headers().add("x-retry-count", new byte[] {'2'});
        runFailingLegacyHandler(batch);
        ArgumentCaptor<ProducerRecord<String, String>> dlt = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(producer).send(dlt.capture(), any());
        assertEquals(TOPIC + ".DLT", dlt.getValue().topic());
        assertEquals("target-7", dlt.getValue().key());
        assertArrayEquals(new byte[] {'3'}, dlt.getValue().headers().lastHeader("x-retry-count").value());
    }

    @Test
    void legacyDisabledDlqDoesNotRepublish() throws Exception {
        provider = spy(new KafkaMqProvider(producer, "unused:9092", GROUP,
                new DeadLetterQueueHandler(producer, ".DLT", 3, false)));
        doReturn(consumer).when(provider).createSubscriptionConsumer(anyString(), anyBoolean());
        runFailingLegacyHandler(records(41));
        verifyNoInteractions(producer);
    }

    private void runFailingLegacyHandler(ConsumerRecords<String, String> batch) throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        doAnswer(invocation -> { closed.countDown(); return null; }).when(consumer).close();
        when(consumer.poll(any(Duration.class))).thenReturn(batch).thenAnswer(invocation -> {
            provider.unsubscribe(TOPIC, GROUP);
            return ConsumerRecords.empty();
        });
        provider.subscribe(TOPIC, GROUP, (topic, body, headers) -> { throw new IllegalArgumentException("legacy failure"); });
        assertTrue(closed.await(5, TimeUnit.SECONDS));
        verify(provider).createSubscriptionConsumer(GROUP, false);
    }

    @Test
    void destroyedProviderRejectsNewSubscriptions() {
        provider.destroy();
        assertThrows(IllegalStateException.class, () -> provider.subscribeDurably(TOPIC, GROUP, record -> fail()));
    }

    private static ConsumerRecords<String, String> records(long... offsets) {
        List<ConsumerRecord<String, String>> records = java.util.Arrays.stream(offsets)
                .mapToObj(offset -> new ConsumerRecord<>(TOPIC, PARTITION.partition(), offset, "target-7", "event-" + offset))
                .toList();
        return new ConsumerRecords<>(Map.of(PARTITION, records));
    }
}
