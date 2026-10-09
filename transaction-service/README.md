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
- İş kuralı hataları (`NonRetryableException`), `application.yml`'deki
  `ignore-exceptions` ayarı sayesinde circuit breaker'ın başarısızlık
  istatistiğine de hiç sayılmaz - yani sık karşılaşılan normal
  kullanıcı hataları (örn. art arda "yetersiz bakiye" denemeleri),
  Account Service gerçekte çökmemişken devrenin yanlışlıkla `OPEN`
  duruma geçmesine yol açmaz. Gerçek bir kesinti senaryosuyla (art arda
  12 başarısız transfer isteği) test edildi (bkz.
  docs/asama8-notlar.md, "Ek Düzeltmeler — İkinci Tur Kod İncelemesi",
  Madde 1).
- `RetryTemplate`, `CircuitBreaker.run(...)`'ı sarmaladığı için, tek bir
  kullanıcı isteği circuit breaker'a birden fazla (retry sayısı kadar)
  ayrı "çağrı" olarak yansıyabilir - bu, gerçek kesinti senaryosunda
  circuit'in bazen bir istek ortasında açılmasına yol açar (bkz.
  docs/asama8-notlar.md, "Gün 3 — Madde 5").
- `minimum-number-of-calls` ayarı `sliding-window-size` ile uyumlu
  (10) hale getirildi - önceden bu ayar tanımlı olmadığı için
  Resilience4j'nin varsayılanı (100) geçerliydi, bu da circuit
  breaker'ın pratikte hiç devreye giremeyeceği anlamına geliyordu.
- **Zaman aşımı (TimeLimiter)**: Account Service çağrısı için 5 saniyelik
  bir zaman aşımı açıkça tanımlıdır (`resilience4j.timelimiter.instances.accountService`).
  Tanımlanmadığında Resilience4J'nin varsayılanı olan 1 saniye geçerlidir;
  soğuk başlayan bir Account Service'in ilk çağrısı bu süreyi aşıyor, çağrı
  burada kesiliyor ama Account Service işi tamamlıyordu: para gidiyor,
  transfer kaydı `FAILED` yazılıyordu. `TimeoutException`, `FeignException`
  olmadığı için retry'ı tetiklemez; bu sayede aynı transfer iki kez
  işlenmedi (bkz. docs/asama8-notlar.md, "TimeLimiter bulgusu"). Kalan
  sınırlama: süre yine aşılırsa aynı tutarsızlık oluşabilir; bunu kapatacak
  bir mutabakat (reconciliation) adımı yapılmadı.

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

Event yayınlanırken isteğin `correlationId`'si Kafka mesajının header'ına
(`X-Correlation-Id`) yazılır; event'in içeriği değişmemiştir. Notification
Service bu header'ı okuyup kendi loglarına yazar.

## Loglama ve correlationId
Gelen her istekteki `X-Correlation-Id` header'ı, `CorrelationIdFilter`
tarafından SLF4J MDC'ye (`correlationId`) konur; header yoksa ya da
şüpheliyse (harf, rakam ve tire dışında karakter, 8–64 karakter dışında
uzunluk) yeni bir UUID üretilir. Id, o isteğe ait tüm log satırlarına
otomatik eklenir (Hibernate SQL logları dahil, bunlar `show-sql` yerine
`org.hibernate.SQL` logger'ı üzerinden yazılır), cevaba da yazılır ve istek
bitince MDC temizlenir.

Account Service'e giden Feign çağrılarında `FeignCorrelationIdInterceptor`
aynı id'yi `X-Correlation-Id` header'ı olarak iletir. Çağrı, TimeLimiter
yüzünden ayrı bir thread'de çalıştığı için `AccountServiceExecutor`, MDC'yi
o thread'e aktarır ve iş bitince temizler. Böylece bir transfer, Gateway →
transaction-service → account-service → notification-service zinciri
boyunca tek bir id ile izlenebilir.

`docker` profilinde (Compose'ta `SPRING_PROFILES_ACTIVE: docker`) loglar
JSON formatındadır ve `correlationId` ayrı bir alandır. `TransactionService`
catch blokları, Account Service çağrısı başarısız olduğunda nedenini
(Feign durumu, circuit breaker açık mı, beklenmeyen hata ve stack trace)
loglar (bkz. docs/asama8-notlar.md, "Yapılandırılmış Loglama ve
correlationId").

## Metrikler
Servis, Micrometer ile Prometheus formatında metrik yayınlar. Metrikler
uygulama portundan (8084) değil, ayrı bir yönetim portundan (9100,
`/actuator/prometheus`) sunulur; bu port host'a yayımlanmaz, yalnızca Docker
network'ü içinden Prometheus okur.

Hazır metriklere (HTTP istekleri, JVM, Resilience4j devre kesici ve
TimeLimiter) ek olarak bir özel sayaç vardır: `banking_transfers_total`.
Hazır HTTP metrikleri transferin iş sonucunu göstermez, çünkü başarısız bir
transfer de HTTP 200 (`status: FAILED`) döner. Sayaç iki etiket taşır:
- `status`: `completed` ya da `failed`
- `reason`: `none` (başarılı), `rejected` (iş kuralı reddi, 4xx),
  `unavailable` (servise ulaşılamadı / zaman aşımı), `circuit_open` (devre
  açık, çağrı yapılmadı), `unexpected` (beklenmeyen hata)

Sayaç, transfer kaydı veritabanına yazıldıktan sonra artar; aynı
`Idempotency-Key` ile gelen tekrar istek (mevcut kaydı döndürür) sayılmaz.
Beş etiket kombinasyonu uygulama açılırken 0 olarak kaydedilir; aksi halde
Prometheus'ta ilk başarısız transfer `rate()` / `increase()` sorgularında
görünmeyebilir. Grafana'daki "Transfer sonuçları" panelleri bu sayaçtan
beslenir (bkz. docs/asama8-notlar.md, "Metrikler ve İzleme (Prometheus +
Grafana)").

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
koruması. Ayrıca `CorrelationIdFilterTest`, `FeignCorrelationIdInterceptorTest`
ve `TransactionEventProducerCorrelationTest`, correlationId'nin üretilmesini,
Feign çağrısına ve Kafka mesajının header'ına taşınmasını ve MDC'nin istek
sonunda temizlenmesini doğrular (bkz. docs/asama8-notlar.md). Ayrıca
`TransactionServiceTest` içindeki dört test `banking_transfers_total`
sayacını doğrular: başarılı transfer `completed/none`, servis hatası
`failed/unavailable`, açık devre `failed/circuit_open` olarak sayılır ve
mevcut bir `Idempotency-Key` ile gelen tekrar istek sayacı artırmaz.

## API Dokümantasyonu
http://localhost:8084/swagger-ui.html
Merkezi (tüm servisler): http://localhost:8080/swagger-ui.html