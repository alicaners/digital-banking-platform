package com.banking.transaction.kafka;

import com.banking.transaction.event.TransactionEvent;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class TransactionEventProducer {

    private static final String TOPIC = "transaction-events";
    static final String CORRELATION_HEADER = "X-Correlation-Id";
    private static final String MDC_KEY = "correlationId";

    private final KafkaTemplate<String, TransactionEvent> kafkaTemplate;

    public TransactionEventProducer(KafkaTemplate<String, TransactionEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(TransactionEvent event) {
        ProducerRecord<String, TransactionEvent> record = new ProducerRecord<>(TOPIC, event);

        // Event'in içeriğine dokunmadan, correlationId'yi Kafka mesaj header'ı olarak taşıyoruz
        String correlationId = MDC.get(MDC_KEY);
        if (correlationId != null && !correlationId.isBlank()) {
            record.headers().add(CORRELATION_HEADER, correlationId.getBytes(StandardCharsets.UTF_8));
        }

        kafkaTemplate.send(record);
    }
}