# Kafka message provider

`aura.mq.type=kafka` activates this optional runtime module. The ordinary
`MqProvider.send/subscribe` SPI retains its asynchronous send, auto-commit and
configured retry/DLT behavior. It does not provide a durable receipt barrier.
Other MQ providers and the shared SPI are unchanged.

## Confirmed keyed sends

```java
CompletionStage<RecordMetadata> acknowledgement = kafka.sendConfirmed(
    "observations.v1", "source-7:target-42", payload, Map.of("event-id", eventId));
```

The caller supplies a nonblank, stable partition key. The stage completes only
when the Kafka callback reports broker acknowledgement, or exceptionally on
either immediate send failure or asynchronous failure. Metadata includes topic,
partition and offset. Production configuration uses `acks=all` and producer
idempotence; broker replication and minimum in-sync replicas remain deployment
decisions. Key ordering is per partition and requires a stable partition count
and partitioning policy.

A local enqueue, cancellation or caller timeout is not proof of delivery or
non-delivery. Application retries need the original immutable event identity.
Broker acknowledgement is not confirmation of downstream persistence or domain
processing. Outbox publishers may mark transport delivery only after confirmed
completion, preserving their existing lease, retry and identity rules.

## Durable receipt consumption

```java
CompletionStage<Void> lifecycle = kafka.subscribeDurably(topic, group, record -> {
    receiver.persistReceiptAndCommit(record.topic(), record.partition(),
        record.offset(), record.key(), record.value());
});
// Observe exceptional completion and alert/restart deliberately after diagnosis.
```

The callback runs synchronously on the consumer thread. Return only after the
receiver's durable transaction commits. Returning after dispatching asynchronous
work, or catching a receipt failure and returning normally, violates this
contract. Keep processing below `max.poll.interval.ms`; slower domain processing
belongs after the durable receipt, under the receiver's existing reliable worker.

Automatic offset commits are disabled. After each successful callback, the
provider synchronously commits only that record's partition and `offset + 1`.
It never commits the entire poll position. Receive or commit failure stops the
subscription and completes its lifecycle stage exceptionally. No retry topic or
DLT bypasses a failed receipt, and no later batch record is delivered after the
failure. Construction and close failures also fail the lifecycle stage.

`unsubscribe` requests a stop; await the returned lifecycle stage before
resubscribing with the same topic/group. The stopping entry remains registered
until its consumer closes. Subscribe again with the same group after diagnosis
to replay from committed offsets. The stage's normal completion means the worker
closed, not that every polled record was processed. `destroy` rejects subsequent
subscriptions. Duplicate durable subscriptions fail explicitly.

A crash, rebalance or uncertain commit can redeliver an already persisted
receipt. Deduplicate using an immutable application event identity; source
topic/partition/offset also identify a transport delivery, but not a retried event
published at a new offset. A database transaction and a Kafka offset commit are
not atomic. Never mix automatic-commit consumers into the durable consumer group.
All group members must use the same receipt semantics.

These methods are Kafka-specific APIs on `KafkaMqProvider`, with Kafka record
and metadata types. They are not added to the generic MQ or Plugin API SPI.
An adapter must depend on this module and its matching runtime. Existing
InstalledIntegration accept/process/result commands, permanent receipts and
reliable Outbox remain responsible for domain authorization and durable work;
an event bus or machine Action does not substitute for that bridge.

## Verification

Hermetic tests (no network or broker):

```sh
./gradlew :platform-mq-kafka:test --tests '*KafkaMqProviderTest' \
  --tests '*KafkaMqReliabilityTest' --tests '*DeadLetterQueueHandlerTest'
```

Host-first seam tests, in an owned, registered runtime:

```sh
aura runtime verify "$runtime"
aura gradle "$runtime" --project "$platform" -- :platform-mq-kafka:reliableBrokerTest
```

The integration task requires `KAFKA_BOOTSTRAP_SERVERS`, runtime-owned
`KAFKA_TOPIC_PREFIX` and `KAFKA_CONSUMER_GROUP_PREFIX`, and the standard
`POSTGRES_HOST/PORT/DB/USER/PASSWORD` variables. Missing prerequisites fail; there
is no Docker fallback or assumption-based skip. Tests retain unique topics,
group offsets and PostgreSQL receipt rows as evidence. They verify keyed broker
acknowledgement, broker rejection, rollback/replay and receipt-before-offset
replay with an idempotent PostgreSQL receiver. They do not exercise the full
InstalledIntegration/domain bridge, multi-broker failover or production recovery.

The existing `KafkaMqProviderIntegrationTest` is a separate historical
Testcontainers suite; it is not part of the host-first task above.
