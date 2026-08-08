package com.banking.notification.kafka;

import com.banking.notification.event.TransactionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class TransactionEventConsumer {

    private static final Logger logger = LoggerFactory.getLogger(TransactionEventConsumer.class);

    @KafkaListener(topics = "transaction-events", groupId = "notification-group")
    public void consume(TransactionEvent event) {

        String message = buildMessage(event);
        logger.info("BİLDİRİM GÖNDERİLDİ: {}", message);
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