# Transaction Service

İki hesap arasında para transferi işlemlerini yöneten servis.

## Çalıştırma
mvnw spring-boot:run

## Port
8084

## Endpoint'ler (Gateway üzerinden JWT token gerektirir)
POST /api/transactions/transfer - İki hesap arasında transfer yapar

## Veritabanı
PostgreSQL - transaction_db

## Servisler Arası İletişim
Account Service'e Feign Client (AccountServiceClient) üzerinden
senkron HTTP çağrıları yapılır.

## Distributed Transaction Yönetimi (Saga Pattern)
Transfer sırasında gönderen hesaptan para düşürüldükten sonra alıcı
hesaba eklenirken bir hata oluşursa, sistem otomatik olarak parayı
gönderen hesaba geri iade eder (compensating transaction). İşlem
durumları:
- COMPLETED: her iki adım da başarılı
- FAILED: withdraw'ın kendisi başarısız oldu (para hiç hareket etmedi)
- REVERSED: withdraw başarılı oldu ama deposit başarısız oldu,
  telafi (geri yatırma) başarıyla yapıldı

Bu, Aşama 3'te tespit edilip Aşama 4'te çözülen bir sorundur
(bkz. docs/asama3-notlar.md ve docs/asama4-notlar.md).

## Dayanıklılık (Resilience)
Account Service çağrısı Circuit Breaker (Resilience4j) ile korunur —
Account Service çökerse, sistem gereksiz yere beklemeden hızlı bir
fallback cevabı döner. Geçici bağlantı hatalarında (ConnectException,
IOException) @Retryable ile otomatik olarak en fazla 3 kez tekrar
deneme yapılır (bkz. docs/asama5-notlar.md).

## Test
Unit testler (Mockito) ile transfer() metodunun dört Saga senaryosu
(COMPLETED, FAILED, REVERSED, telafi başarısız) kapsanmıştır
(bkz. docs/asama6-notlar.md).

## API Dokümantasyonu
http://localhost:8084/swagger-ui.html
Merkezi (tüm servisler): http://localhost:8080/swagger-ui.html