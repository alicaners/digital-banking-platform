# Notification Service

Transaction Service'ten Kafka üzerinden gelen işlem olaylarını
dinleyip bildirim simülasyonu yapan servis.

## Çalıştırma
mvnw spring-boot:run

## Port
8085

## Not
Bu servisin dışarıya açık bir REST endpoint'i yoktur, bu yüzden
Gateway route'unda (API isteklerine yönelik) yer almaz. Sadece
Kafka'daki transaction-events topic'ini dinler.

## Kafka
Topic: transaction-events
Consumer group: notification-group

Transaction Service, event'i yayınlarken isteğin `correlationId`'sini Kafka
mesajının header'ına (`X-Correlation-Id`) yazar; event'in kendi içeriği
değişmemiştir. Consumer bu header'ı okur, Gateway'deki aynı kuralla doğrular
(harf, rakam ve tire, 8–64 karakter) ve geçerliyse MDC'ye koyar. Header yoksa
ya da şüpheliyse log satırına id yazılmaz. MDC her mesajdan sonra temizlenir,
çünkü listener thread'i sonraki mesajda yeniden kullanılır.

## Bildirim Simülasyonu
Gerçek email/SMS gönderimi yapılmaz, bilinçli olarak sadece log'a
anlamlı bir mesaj yazılır. `docker` profilinde (Compose'ta
`SPRING_PROFILES_ACTIVE: docker`) loglar JSON formatındadır ve
`correlationId` ayrı bir alandır; böylece bir bildirimin hangi transfer
isteğinden geldiği, Gateway ve diğer servislerin loglarındaki aynı id
ile eşleştirilebilir (bkz. docs/asama8-notlar.md, "Yapılandırılmış Loglama
ve correlationId").

## Test
`TransactionEventConsumerTest`, header'daki id'nin log satırına
yazıldığını ve MDC'nin işlem sonunda temizlendiğini, header olmadığında ya
da değer şüpheli olduğunda log satırına id yazılmadığını doğrular.
`NotificationServiceApplicationTests` ise context-load testidir.

## API Dokümantasyonu
Servisin kendi endpoint'i olmadığı için Swagger sayfası boş görünür,
ancak tutarlılık için springdoc-openapi eklendi ve merkezi Gateway
Swagger sayfasındaki dropdown'da listelenir:
http://localhost:8080/swagger-ui.html