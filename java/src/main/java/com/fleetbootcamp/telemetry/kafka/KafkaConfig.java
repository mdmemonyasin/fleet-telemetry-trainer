package com.fleetbootcamp.telemetry.kafka;

import com.fleetbootcamp.telemetry.Topics;
import com.fleetbootcamp.telemetry.kafka.EventParser.InvalidEventException;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConfig {

    /**
     * Spring Boot plugs this into the listener container automatically.
     * - InvalidEventException (bad JSON / unknown schema): no retry, straight to telemetry.dlq
     * - anything else (e.g. DB briefly down): retry 3 times, 1s apart, then telemetry.dlq
     * Either way one bad message can never block its partition.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        DeadLetterPublishingRecoverer toDlq =
                new DeadLetterPublishingRecoverer(template, (rec, ex) -> new TopicPartition(Topics.DLQ, -1));
        DefaultErrorHandler handler = new DefaultErrorHandler(toDlq, new FixedBackOff(1000L, 3L));
        handler.addNotRetryableExceptions(InvalidEventException.class);
        return handler;
    }
}
