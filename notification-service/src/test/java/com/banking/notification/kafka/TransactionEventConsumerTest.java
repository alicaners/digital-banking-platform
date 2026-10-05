package com.banking.notification.kafka;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.banking.notification.event.TransactionEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TransactionEventConsumerTest {

    private final TransactionEventConsumer consumer = new TransactionEventConsumer();
    private ListAppender<ILoggingEvent> appender;
    private Logger consumerLogger;

    @BeforeEach
    void attachAppender() {
        consumerLogger = (Logger) LoggerFactory.getLogger(TransactionEventConsumer.class);
        appender = new ListAppender<>();
        appender.start();
        consumerLogger.addAppender(appender);
    }

    @AfterEach
    void cleanup() {
        consumerLogger.detachAppender(appender);
        MDC.clear();
    }

    private ConsumerRecord<String, TransactionEvent> recordWithHeader(String headerValue) {
        TransactionEvent event = mock(TransactionEvent.class);
        when(event.getStatus()).thenReturn("COMPLETED");
        ConsumerRecord<String, TransactionEvent> record = new ConsumerRecord<>("transaction-events", 0, 0L, null, event);
        if (headerValue != null) {
            record.headers().add("X-Correlation-Id", headerValue.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }

    @Test
    void headerPresent_logLineCarriesCorrelationId_andMdcIsClearedAfterwards() {
        consumer.consume(recordWithHeader("transfer-test-0007"));

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getMDCPropertyMap()).containsEntry("correlationId", "transfer-test-0007");
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void headerMissing_logLineHasNoCorrelationId() {
        consumer.consume(recordWithHeader(null));

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getMDCPropertyMap()).doesNotContainKey("correlationId");
    }

    @Test
    void suspiciousHeader_isIgnored() {
        consumer.consume(recordWithHeader("bad value\nwith newline"));

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getMDCPropertyMap()).doesNotContainKey("correlationId");
    }
}