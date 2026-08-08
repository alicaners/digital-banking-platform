package com.banking.transaction.kafka;

import com.banking.transaction.event.TransactionEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class TransactionEventProducer {

    private static final String TOPIC = "transaction-events";

    @Autowired
    private KafkaTemplate<String, TransactionEvent> kafkaTemplate;

    public void publish(TransactionEvent event) {
        kafkaTemplate.send(TOPIC, event);
    }
}