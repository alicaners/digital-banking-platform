package com.banking.transaction.kafka;

import com.banking.transaction.event.TransactionEvent;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TransactionEventProducerCorrelationTest {

    @Mock
    private KafkaTemplate<String, TransactionEvent> kafkaTemplate;

    @AfterEach
    void cleanup() {
        MDC.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void mdcHasCorrelationId_recordCarriesItAsHeader() {
        MDC.put("correlationId", "transfer-test-0007");
        TransactionEventProducer producer = new TransactionEventProducer(kafkaTemplate);

        producer.publish(new TransactionEvent(1L, 19L, 20L, new BigDecimal("10"), "COMPLETED"));

        ArgumentCaptor<ProducerRecord<String, TransactionEvent>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        Header header = captor.getValue().headers().lastHeader("X-Correlation-Id");
        assertThat(header).isNotNull();
        assertThat(new String(header.value(), StandardCharsets.UTF_8)).isEqualTo("transfer-test-0007");
    }

    @Test
    @SuppressWarnings("unchecked")
    void mdcEmpty_recordHasNoCorrelationHeader() {
        TransactionEventProducer producer = new TransactionEventProducer(kafkaTemplate);

        producer.publish(new TransactionEvent(1L, 19L, 20L, new BigDecimal("10"), "COMPLETED"));

        ArgumentCaptor<ProducerRecord<String, TransactionEvent>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        assertThat(captor.getValue().headers().lastHeader("X-Correlation-Id")).isNull();
    }
}