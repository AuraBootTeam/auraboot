package com.auraboot.framework.behavior.ingest;

import com.auraboot.framework.infrastructure.mq.MqProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BehaviorKafkaTopicPolicyTest {

    @Test
    void topicAndConsumerGroupNamesMatchProductionRunbook() {
        MqProperties.Kafka.DeadLetter deadLetter = new MqProperties().getKafka().getDeadLetter();

        assertThat(BehaviorIngestPublisher.TOPIC_EVENTS).isEqualTo("aura.behavior.events.v1");
        assertThat(BehaviorIngestPublisher.TOPIC_QUARANTINE).isEqualTo("aura.behavior.quarantine.v1");
        assertThat(BehaviorIngestConsumer.CONSUMER_GROUP).isEqualTo("aura-behavior-ingest");
        assertThat(BehaviorQuarantineConsumer.CONSUMER_GROUP).isEqualTo("aura-behavior-quarantine");
        assertThat(deadLetter.getTopicSuffix()).isEqualTo(".DLT");
        assertThat(deadLetter.getMaxAttempts()).isEqualTo(3);
        assertThat(deadLetter.isEnabled()).isTrue();
    }
}
