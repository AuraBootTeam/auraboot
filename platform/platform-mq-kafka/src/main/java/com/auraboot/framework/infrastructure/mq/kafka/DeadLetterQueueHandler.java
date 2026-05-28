package com.auraboot.framework.infrastructure.mq.kafka;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;

import java.nio.charset.StandardCharsets;

/**
 * Routes failed consumer messages to a Dead Letter Topic (DLT).
 * <p>
 * Activated by {@code aura.mq.kafka.dead-letter.enabled=true} (default).
 * After {@code maxAttempts} delivery attempts (tracked via the {@code retry_count}
 * record header), the message + error metadata are produced to {@code <topic><topicSuffix>}.
 * </p>
 *
 * <h3>DLT record headers</h3>
 * <ul>
 *   <li>{@code x-original-topic} — source topic</li>
 *   <li>{@code x-original-partition} / {@code x-original-offset}</li>
 *   <li>{@code x-error-class} / {@code x-error-message}</li>
 *   <li>{@code x-retry-count} — final attempt count</li>
 *   <li>Plus the original record headers preserved verbatim.</li>
 * </ul>
 */
@Slf4j
public class DeadLetterQueueHandler {

    static final String RETRY_COUNT_HEADER = "x-retry-count";
    static final String ORIGINAL_TOPIC_HEADER = "x-original-topic";
    static final String ORIGINAL_PARTITION_HEADER = "x-original-partition";
    static final String ORIGINAL_OFFSET_HEADER = "x-original-offset";
    static final String ERROR_CLASS_HEADER = "x-error-class";
    static final String ERROR_MESSAGE_HEADER = "x-error-message";

    private final KafkaProducer<String, String> producer;
    private final String topicSuffix;
    private final int maxAttempts;
    private final boolean enabled;

    public DeadLetterQueueHandler(KafkaProducer<String, String> producer,
                                   String topicSuffix,
                                   int maxAttempts,
                                   boolean enabled) {
        this.producer = producer;
        this.topicSuffix = topicSuffix == null || topicSuffix.isBlank() ? ".DLT" : topicSuffix;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    /**
     * Extract current retry_count from record headers (defaults to 0 if absent).
     */
    public static int currentRetryCount(ConsumerRecord<String, String> record) {
        Header h = record.headers().lastHeader(RETRY_COUNT_HEADER);
        if (h == null || h.value() == null) {
            return 0;
        }
        try {
            return Integer.parseInt(new String(h.value(), StandardCharsets.UTF_8));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Decide whether the next failure should route to DLT.
     *
     * @param currentAttempts attempts already made (including the failed one)
     * @return true if {@code currentAttempts >= maxAttempts}
     */
    public boolean shouldRouteToDlt(int currentAttempts) {
        return enabled && currentAttempts >= maxAttempts;
    }

    /**
     * Produce the failed record to {@code <topic><topicSuffix>} with error metadata headers.
     */
    public void routeToDeadLetter(ConsumerRecord<String, String> record, Throwable error, int attempts) {
        if (!enabled) {
            return;
        }
        String dltTopic = record.topic() + topicSuffix;
        ProducerRecord<String, String> dlt = new ProducerRecord<>(dltTopic, record.key(), record.value());

        // Preserve original headers
        for (Header h : record.headers()) {
            if (!isReservedHeader(h.key())) {
                dlt.headers().add(h.key(), h.value());
            }
        }
        dlt.headers().add(ORIGINAL_TOPIC_HEADER, record.topic().getBytes(StandardCharsets.UTF_8));
        dlt.headers().add(ORIGINAL_PARTITION_HEADER,
                Integer.toString(record.partition()).getBytes(StandardCharsets.UTF_8));
        dlt.headers().add(ORIGINAL_OFFSET_HEADER,
                Long.toString(record.offset()).getBytes(StandardCharsets.UTF_8));
        dlt.headers().add(RETRY_COUNT_HEADER,
                Integer.toString(attempts).getBytes(StandardCharsets.UTF_8));
        if (error != null) {
            dlt.headers().add(ERROR_CLASS_HEADER,
                    error.getClass().getName().getBytes(StandardCharsets.UTF_8));
            String msg = error.getMessage() == null ? "" : error.getMessage();
            dlt.headers().add(ERROR_MESSAGE_HEADER, msg.getBytes(StandardCharsets.UTF_8));
        }

        producer.send(dlt, (md, ex) -> {
            if (ex != null) {
                log.error("Failed to publish DLT record: topic={}, offset={}", dltTopic, record.offset(), ex);
            } else {
                log.warn("Routed failed record to DLT: topic={}, partition={}, offset={}, attempts={}, error={}",
                        dltTopic, md.partition(), md.offset(), attempts,
                        error == null ? "<none>" : error.getMessage());
            }
        });
    }

    private static boolean isReservedHeader(String key) {
        return RETRY_COUNT_HEADER.equals(key)
                || ORIGINAL_TOPIC_HEADER.equals(key)
                || ORIGINAL_PARTITION_HEADER.equals(key)
                || ORIGINAL_OFFSET_HEADER.equals(key)
                || ERROR_CLASS_HEADER.equals(key)
                || ERROR_MESSAGE_HEADER.equals(key);
    }
}
