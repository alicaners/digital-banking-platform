# Transaction Service

İki hesap arasında para transferi işlemlerini yöneten servis.

## Çalıştırma
mvnw spring-boot:run

## Port
8084

## Endpoint'ler (Gateway üzerinden JWT token gerektirir)
POST /api/transactions/transfer - İki hesap arasında transfer yapar
- Header: Idempotency-Key (zorunlu) - istemci tarafından üretilen,
  her transfer denemesi için benzersiz bir anahtar. Aynı key ile
  tekrar gönderilen istek, yeni bir transfer yapmadan önceki
  sonucu döndürür (bkz. "Idempotency" bölümü).

## Veritabanı
PostgreSQL - transaction_db

## Servisler Arası İletişim
Account Service'e Feign Client (AccountServiceClient) üzerinden
senkron HTTP çağrıları yapılır.

## Atomic Transfer (Aşama 8'de basitleştirildi)
Transfer işlemi artık Account Service içinde tek bir `@Transactional`
veritabanı işlemi olarak gerçekleşiyor - sender ve receiver hesapları
aynı veritabanında (account_db) olduğu için, para düşüşü ve artışı
ya birlikte başarılı olur ya da hiçbiri gerçekleşmez; bu veritabanının
kendi ACID garantisiyle sağlanıyor.

Bu, önceden (Aşama 4) bir Saga/compensating-transaction pattern'iyle
(withdraw + deposit + hata durumunda geri iade) çözülüyordu. Sender ve
receiver aynı veritabanında olduğu için Saga'ya gerek olmadığı
belirlendi ve tasarım basitleştirildi (bkz. docs/asama8-notlar.md,
"Gün 2 — Konu A"). Saga pattern, gerçek dünyada farklı veritabanlarına/
servislere yayılmış işlemler için hâlâ geçerli bir çözümdür - bizim
senaryomuzda böyle bir dağıtıklık olmadığı için kaldırıldı.

İşlem durumları artık sadece:
- COMPLETED: transfer başarıyla gerçekleşti
- FAILED: transfer gerçekleşmedi (yetersiz bakiye, yetkisiz erişim,
  hesap bulunamadı, Account Service'e ulaşılamadı vb.)

## Idempotency
Her transfer isteği, istemcinin ürettiği bir `Idempotency-Key` header'ı
taşımak zorundadır. Bu key veritabanında (`transactions.idempotency_key`,
unique) saklanır. Aynı key ile gelen bir istek tekrar işlenmez, ilk
denemenin sonucu doğrudan döndürülür - bu, ağ hatası/timeout sonrası
istemcinin isteği güvenle tekrar gönderebilmesini sağlar
(bkz. docs/asama8-notlar.md, "Gün 2 — Konu B").

## Dayanıklılık (Resilience)
Account Service çağrısı, `AccountServiceExecutor` üzerinden Circuit
Breaker (Resilience4j, açıkça `accountService` adıyla) ve Retry
(Spring Retry, programatik `RetryTemplate`) ile korunur:
- Circuit `OPEN` durumdayken (Account Service çok fazla art arda hata
  verdiğinde) çağrı hiç yapılmadan hızlıca reddedilir.
- Sadece geçici (5xx/bağlantı) hatalar en fazla 3 kez, 500ms arayla
  tekrar denenir. İş kuralı hataları (400/403/404 gibi 4xx) hiç tekrar
  denenmez, çünkü sonuç değişmeyecektir.

Aşama 5'te eklenen `@Retryable` kullanımı, self-invocation (AOP proxy)
kısıtlaması nedeniyle aslında hiç tetiklenmiyordu; Aşama 8'de programatik
`RetryTemplate`'e geçilerek bu sorun kökten çözüldü. Aynı şekilde,
Feign'in otomatik circuit breaker'ının ürettiği isim, `application.yml`
config'iyle eşleşmiyordu (bkz. docs/asama8-notlar.md, "Gün 2 — Konu B").

## Test
Unit testler (Mockito), yeni sadeleştirilmiş transfer akışını
(başarı, hata, doğru request'in gönderilmesi, idempotency-key ile
tekrar gönderilen isteğin cache'lenmiş sonucu döndürmesi) kapsar
(bkz. docs/asama8-notlar.md).

## API Dokümantasyonu
http://localhost:8084/swagger-ui.html
Merkezi (tüm servisler): http://localhost:8080/swagger-ui.html