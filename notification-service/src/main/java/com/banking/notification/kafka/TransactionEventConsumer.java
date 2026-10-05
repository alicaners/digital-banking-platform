package com.banking.notification.kafka;

import com.banking.notification.event.TransactionEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

@Component
public class TransactionEventConsumer {

    private static final Logger logger = LoggerFactory.getLogger(TransactionEventConsumer.class);

    private static final String CORRELATION_HEADER = "X-Correlation-Id";
    private static final String MDC_KEY = "correlationId";
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

    @KafkaListener(topics = "transaction-events", groupId = "notification-group")
    public void consume(ConsumerRecord<String, TransactionEvent> record) {

        TransactionEvent event = record.value();
        String correlationId = readCorrelationId(record);

        if (correlationId != null) {
            MDC.put(MDC_KEY, correlationId);
        }
        try {
            String message = buildMessage(event);
            logger.info("BİLDİRİM GÖNDERİLDİ: {}", message);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private String readCorrelationId(ConsumerRecord<String, TransactionEvent> record) {
        Header header = record.headers().lastHeader(CORRELATION_HEADER);
        if (header == null || header.value() == null) {
            return null;
        }
        String value = new String(header.value(), StandardCharsets.UTF_8);
        // Gateway ile aynı kural: şüpheli/uzun değerler loglara yazılmaz
        return SAFE_ID.matcher(value).matches() ? value : null;
    }

    private String buildMessage(TransactionEvent event) {
        return switch (event.getStatus()) {
            case "COMPLETED" -> "İşlem #" + event.getTransactionId() + " başarıyla tamamlandı: "
                    + event.getAmount() + " TL, hesap " + event.getSenderAccountId()
                    + " -> hesap " + event.getReceiverAccountId();
            case "FAILED" -> "İşlem #" + event.getTransactionId() + " başarısız oldu.";
            case "REVERSED" -> "İşlem #" + event.getTransactionId() + " geri alındı, "
                    + event.getAmount() + " TL hesabınıza iade edildi.";
            default -> "İşlem #" + event.getTransactionId() + " durumu: " + event.getStatus();
        };
    }
}