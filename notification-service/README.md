# Notification Service

Transaction Service'ten Kafka üzerinden gelen işlem olaylarını
dinleyip bildirim simülasyonu yapan servis.

## Çalıştırma
mvnw spring-boot:run

## Port
8085

## Not
Bu servisin dışarıya açık bir REST endpoint'i yoktur, bu yüzden
Gateway route'unda yer almaz. Sadece Kafka'daki transaction-events
topic'ini dinler.

## Kafka
Topic: transaction-events
Consumer group: notification-group

## Bildirim Simülasyonu
Gerçek email/SMS gönderimi yapılmaz, bilinçli olarak sadece log'a
anlamlı bir mesaj yazılır.