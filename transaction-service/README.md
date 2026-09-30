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
senkron HTTP çağrıları yapılır. Bu çağrı, Account Service'in
`/api/accounts/internal/transfer` endpoint'ine gider - bu endpoint,
Gateway seviyesinde dışarıdan erişime tamamen kapalıdır (bkz.
docs/asama8-notlar.md, "Gün 3 — Madde 1"), sadece servisler arası
(Eureka/Feign üzerinden) erişilebilir.

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
  hesap bulunamadı, Account Service'e ulaşılamadı vb.). Başarısızlık
  nedeni (`failureReason`) artık veritabanında kalıcı olarak saklanıyor
  - aynı `Idempotency-Key` ile tekrar istek geldiğinde bile doğru
    şekilde döner (bkz. docs/asama8-notlar.md, "Gün 3 — Madde 7.2").

`status` alanı artık düz bir `String` değil, `TransactionStatus`
enum'u (`PENDING`, `COMPLETED`, `FAILED`) - veritabanında
`@Enumerated(EnumType.STRING)` ile yine okunabilir string olarak
saklanıyor, ama Java tarafında yazım hatasına kapalı, derleyici
tarafından denetlenen bir tip (bkz. docs/asama8-notlar.md,
"Gün 4 — Madde 5.1").

## Validasyonlar
- Gönderen ve alıcı hesap aynı olamaz (`senderAccountId == receiverAccountId`
  ise `400 Bad Request`, bkz. "Gün 3 — Madde 4").
- Aynı `Idempotency-Key` ile, **daha önce kullanıldığından farklı** bir
  transfer body'si (farklı tutar/hesap) gönderilirse `409 Conflict`
  döner - idempotency key'in "aynı isteğin güvenli tekrarı" anlamına
  gelmesi gerektiği için, farklı bir işlem için yeniden kullanılması
  bir hata olarak kabul edilir (bkz. "Gün 3 — Madde 3").

## Idempotency
Her transfer isteği, istemcinin ürettiği bir `Idempotency-Key` header'ı
taşımak zorundadır. Bu key, veritabanında **kullanıcı bazında**
(`(user_id, idempotency_key)` birleşik unique kısıtı) saklanır - yani
iki farklı kullanıcı aynı key string'ini tesadüfen kullansa bile
birbirlerinin transfer kaydını göremezler (bkz. docs/asama8-notlar.md,
"Gün 3 — Madde 2"; ilk tasarım "Gün 2 — Konu B"). Aynı kullanıcının aynı
key ile tekrar gönderdiği istek tekrar işlenmez, ilk denemenin sonucu
doğrudan döndürülür - bu, ağ hatası/timeout sonrası istemcinin isteği
güvenle tekrar gönderebilmesini sağlar.

## Dayanıklılık (Resilience)
Account Service çağrısı, `AccountServiceExecutor` üzerinden Circuit
Breaker (Resilience4j, açıkça `accountService` adıyla) ve Retry
(Spring Retry, programatik `RetryTemplate`) ile korunur:
- Circuit `OPEN` durumdayken (Account Service çok fazla art arda hata
  verdiğinde) çağrı hiç yapılmadan hızlıca reddedilir.
- Sadece geçici (5xx/bağlantı) hatalar en fazla 3 kez, 500ms arayla
  tekrar denenir. İş kuralı hataları (400/403/404 gibi 4xx) hiç tekrar
  denenmez, çünkü sonuç değişmeyecektir.
- `RetryTemplate`, `CircuitBreaker.run(...)`'ı sarmaladığı için, tek bir
  kullanıcı isteği circuit breaker'a birden fazla (retry sayısı kadar)
  ayrı "çağrı" olarak yansıyabilir - bu, gerçek kesinti senaryosunda
  circuit'in bazen bir istek ortasında açılmasına yol açar (bkz.
  docs/asama8-notlar.md, "Gün 3 — Madde 5").
- `minimum-number-of-calls` ayarı `sliding-window-size` ile uyumlu
  (10) hale getirildi - önceden bu ayar tanımlı olmadığı için
  Resilience4j'nin varsayılanı (100) geçerliydi, bu da circuit
  breaker'ın pratikte hiç devreye giremeyeceği anlamına geliyordu.

Aşama 5'te eklenen `@Retryable` kullanımı, self-invocation (AOP proxy)
kısıtlaması nedeniyle aslında hiç tetiklenmiyordu; Aşama 8'de programatik
`RetryTemplate`'e geçilerek bu sorun kökten çözüldü. Aynı şekilde,
Feign'in otomatik circuit breaker'ının ürettiği isim, `application.yml`
config'iyle eşleşmiyordu (bkz. docs/asama8-notlar.md, "Gün 2 — Konu B").

Circuit Breaker ve Retry'ın gerçek bir servis kesintisi senaryosunda
(Account Service bilerek durdurularak) uçtan uca test edildiği ve
CLOSED → OPEN → HALF-OPEN → CLOSED yaşam döngüsünün doğrulandığı
detaylar için bkz. docs/asama8-notlar.md, "Gün 3 — Madde 5".

## Kafka Event Yayını
Her transfer sonrası bir `TransactionEvent` Kafka'ya yayınlanır
(bildirim servisi tüketir). Bu yayın işlemi kendi `try/catch` bloğunda
izole edilmiştir - Kafka geçici olarak kullanılamıyorsa, transfer sonucu
(zaten veritabanına kaydedilmiş olan gerçek durum) yine de client'a
doğru şekilde döner, sadece yayın hatası loglanır. Önceden bu koruma
olmadığı için, başarılı bir transfer bile Kafka kesintisinde client'a
yanlışlıkla `500` olarak dönebiliyordu (bkz. docs/asama8-notlar.md,
"Gün 3 — Madde 7.1").

`TransactionEvent`'in `status` alanı, `notification-service`'deki ayrı
kopyasıyla uyumluluk için kasıtlı olarak `String` kaldı - yayın
sırasında `transaction.getStatus().name()` ile `TransactionStatus`
enum'undan string'e çevriliyor (bkz. docs/asama8-notlar.md,
"Gün 4 — Madde 5.1").

## Eşzamanlılık (Optimistic Locking)
Account Service tarafında, aynı hesabın eşzamanlı güncellenmeye
çalışılması durumunda oluşan çakışmalar (`OptimisticLockException`),
artık çirkin bir `500` yerine anlaşılır bir `409 Conflict` mesajı
olarak bu servise (ve dolayısıyla client'a) yansır (bkz.
docs/asama8-notlar.md, "Gün 3 — Madde 6").

## Test Ortamı Notu
Türkçe işletim sistemi locale'inde çalışan bir JVM'de, embedded Kafka
testi (`TransactionServiceApplicationTests`) Kafka'nın kendi iç kodundaki
bir `toUpperCase()` çağrısı yüzünden başarısız olabiliyordu (Türkçe
locale'de "i" büyütüldüğünde noktalı "İ" üretiliyor, Kafka'nın beklediği
`CLASSIC` yerine geçersiz bir `CLASSİC` enum değeri oluşuyordu). Bu,
`pom.xml`'deki `maven-surefire-plugin` yapılandırmasına eklenen
`-Duser.language=en -Duser.country=US` `argLine`'ı ile çözüldü - bu
sadece **test JVM'inin** case-conversion kurallarını İngilizce'ye
zorluyor, uygulamanın kendi Türkçe davranışını/metinlerini etkilemiyor
(bkz. docs/asama8-notlar.md, "Gün 4 — Madde 4").

## Test
Unit testler (Mockito), transfer akışının tüm senaryolarını kapsar:
başarı, hata, doğru request'in gönderilmesi, idempotency-key ile
tekrar gönderilen isteğin cache'lenmiş sonucu döndürmesi, farklı
kullanıcıların aynı key'i bağımsız kullanabilmesi, aynı key + farklı
body'nin 409 Conflict vermesi, Kafka yayın hatasının transferi
etkilememesi, ve cache'ten dönen bir FAILED transferin `failureReason`'ını
korumas (bkz. docs/asama8-notlar.md).

## API Dokümantasyonu
http://localhost:8084/swagger-ui.html
Merkezi (tüm servisler): http://localhost:8080/swagger-ui.html