# Account Service

Müşteri hesaplarının açıldığı ve bakiye işlemlerinin yönetildiği servis.

## Çalıştırma
mvnw spring-boot:run

## Port
8083

## Endpoint'ler (Gateway üzerinden JWT token gerektirir)
POST /api/accounts - Yeni hesap aç (verilen `customerId`'nin Customer
Service'te gerçekten var olduğu doğrulanır; yoksa `404 Not Found`
döner, bkz. "Teknik Notlar")
GET /api/accounts/{id} - Hesap bilgisi ve bakiye sorgula (sahiplik kontrolü var)
GET /api/accounts - Kendi hesaplarını sayfalı şekilde listele (ADMIN tüm
hesapları görür). Query parametreleri: `?page=0&size=20&sort=id,desc`
(hiçbiri verilmezse varsayılan: sayfa 0, boyut 20) - bkz. "Sayfalama"
POST /api/accounts/{id}/deposit - Hesaba para yatır (sahiplik kontrolü yok - kasıtlı)
POST /api/accounts/{id}/withdraw - Hesaptan para çek (sahiplik kontrolü var)
POST /api/accounts/internal/transfer - İki hesap arasında atomic transfer
(yalnızca Transaction Service tarafından, Feign üzerinden çağrılır;
sender hesabında sahiplik kontrolü var)

**Not**: `/internal/` altındaki tüm endpoint'ler (örn. `internal/transfer`)
Gateway seviyesinde dışarıdan gelen isteklere kapatılmıştır - sadece
servisler arası (container-to-container) çağrılara açıktır
(bkz. docs/asama8-notlar.md, "Gün 3 — Madde 1").

## Veritabanı
PostgreSQL - account_db

## Yetkilendirme (Authorization)
Her hesap, `userId` alanıyla bir kullanıcıya bağlıdır (Gateway'in
JWT'den çıkarıp `X-User-Id` header'ıyla ilettiği kimlik). Kurallar:
- **Okuma** (`getById`, `getAll`): ADMIN rolü herkesin hesabını
  görebilir, diğer kullanıcılar sadece kendi hesaplarını.
- **Yazma** (`withdraw`, transfer'de sender): ADMIN dahil, sadece
  hesabın gerçek sahibi işlem yapabilir - admin'e kısıtlı (sadece
  okuma) yetki verildi, bu bilinçli bir tasarım kararı.
- **İstisna - `deposit`**: Sahiplik kontrolü kasıtlı olarak yok, çünkü
  bir hesaba para yatırmak (örn. birinin size transfer yapması), o
  hesabın sahibi olmayı gerektirmemeli.

Yetkisiz bir işlem denemesi `403 Forbidden` döner (`AccessDeniedException`).
Detaylı gerekçe için bkz. docs/asama8-notlar.md, "Gün 1 — Konu B".

Rol kontrolü (`Role.valueOf(role) == Role.ADMIN`), Gateway'in `X-User-Role`
header'ıyla ilettiği string'i tip güvenli bir `Role` enum'una (`CUSTOMER`,
`ADMIN`) çevirerek yapılır. Bilinçli bir tasarım kararı: `role` header'ı
Gateway'in kendi doğruladığı JWT'den türediği için normalde her zaman
geçerli bir değerdir; beklenmedik bir değer gelirse (bug/veri bütünlüğü
sorunu) sistem sessizce "yetkisiz" varsaymak yerine `400 Bad Request`
ile açıkça hata verir (bkz. docs/asama8-notlar.md, "Gün 4 — Madde 5.5").

## Atomic Transfer (Aşama 8'de eklendi)
`internal/transfer` endpoint'i, sender ve receiver hesapları arasındaki
para transferini tek bir `@Transactional` veritabanı işlemi içinde
gerçekleştirir - ikisi de aynı veritabanında olduğu için, düşüş ve
artış ya birlikte başarılı olur ya da hiçbiri gerçekleşmez. Bu, önceden
Transaction Service'te bir Saga pattern'iyle yönetiliyordu; aynı
veritabanına sahip olmaları nedeniyle bu basitleştirmeye gidildi
(bkz. docs/asama8-notlar.md, "Gün 2 — Konu A").

## Sayfalama
`GET /api/accounts`, DB'deki tüm kayıtları tek seferde dönmek yerine
Spring Data'nın `Pageable`/`Page` desteğiyle sayfalanmış sonuç döner.
CUSTOMER rolü için filtreleme artık DB seviyesinde (`findByUserId`)
yapılır, önceden olduğu gibi tüm kayıtları çekip Java tarafında
filtrelemez - bu hem performans hem veri transferi açısından daha
verimlidir (bkz. docs/asama8-notlar.md, "Gün 4 — Madde 6").

## Teknik Notlar

**Müşteri varlık kontrolü**: Hesap açılırken (`POST /api/accounts`),
verilen `customerId`'nin Customer Service'te gerçekten kayıtlı olup
olmadığı, Feign Client (`CustomerServiceClient`) ile senkron olarak
kontrol edilir. Müşteri bulunamazsa `404 Not Found` döner. Genel
olarak "kayıt bulunamadı" durumları (`ResourceNotFoundException`,
hesap/gönderen/alıcı/müşteri) `404`, gerçek iş kuralı hataları
(yetersiz bakiye, negatif tutar vb.) `400 Bad Request` olarak ayrıştırılmıştır
(bkz. docs/asama8-notlar.md, "Gün 4 — Madde 1").

**Hesap durumu**: `Account.status` alanı, düz bir `String` yerine
`AccountStatus` enum'u (şu an için sadece `ACTIVE` değerine sahip -
kod başka bir değer üretmiyor) - veritabanında yine okunabilir string
olarak saklanır, Java tarafında yazım hatasına kapalıdır (bkz.
docs/asama8-notlar.md, "Gün 4 — Madde 5.2").

**Para miktarları**: Tüm bakiye alanları `BigDecimal` ile tutulur,
`double`/`float` kullanılmaz — ondalık yuvarlama hatalarının önüne
geçmek için.

**Eşzamanlılık**: `Account` entity'sinde `@Version` alanı ile
optimistic locking uygulanır: iki eşzamanlı işlem aynı hesabı
güncellemeye çalıştığında, Hibernate ikinci (geç kalan) işlemi
`ObjectOptimisticLockingFailureException` ile reddeder. Bu exception,
`GlobalExceptionHandler`'da yakalanıp kullanıcıya anlaşılır bir
Türkçe mesajla `409 Conflict` olarak döndürülür ("Bu hesap üzerinde
eşzamanlı bir işlem gerçekleşti, lütfen tekrar deneyin"). Davranış
kod seviyesinde doğrulandı; gerçek eşzamanlı çakışma senaryosu Postman
ile manuel test edilemedi çünkü istekler arasındaki gecikme çakışmayı
tetiklemeye yetmedi (bkz. docs/asama8-notlar.md, "Gün 3 — Madde 6").

**IBAN üretimi (basitleştirilmiş)**: Bu projede IBAN'lar, gerçek
ISO 7064 (MOD 97-10) checksum algoritması ve resmi banka kodları
kullanılmadan, formatça gerçekçi görünen rastgele sayılarla üretilir.
Gerçek bir bankacılık sisteminde bu, merkez bankası tarafından
sağlanan resmi kod listeleri ve checksum doğrulaması gerektirir —
bu proje kapsamında bilinçli olarak basitleştirilmiştir.

## Dayanıklılık (Customer Service Çağrısı)
Hesap açılırken yapılan Customer Service çağrısı (`checkCustomerExists`),
`transaction-service`'in Account Service'e yaptığı çağrıyla aynı
desende bir `CustomerServiceExecutor` üzerinden, açıkça isimlendirilmiş
(`customerService`) bir Resilience4j Circuit Breaker ve programatik
`RetryTemplate` (Spring Retry) ile korunur:
- Customer Service'ten gelen 4xx hatalar (örn. müşteri bulunamadı)
  `NonRetryableException`'a sarılır - ne tekrar denenir ne circuit
  breaker istatistiğine sayılır, çünkü sonuç değişmeyecektir.
- Sadece geçici (5xx/bağlantı) hatalar en fazla 3 kez, 500ms arayla
  tekrar denenir; devre açıldığında (`OPEN`) çağrı hiç yapılmadan
  hızlıca reddedilir.
- Çağrı için 5 saniyelik bir zaman aşımı (TimeLimiter) açıkça tanımlıdır
  (`resilience4j.timelimiter.instances.customerService`). Tanımlanmadığında
  Resilience4J'nin varsayılanı olan 1 saniye, yavaş yanıtlarda çağrının
  boşuna kesilmesine yol açar (bkz. docs/asama8-notlar.md,
  "TimeLimiter bulgusu").

(bkz. docs/asama8-notlar.md, "Ek Düzeltmeler — İkinci Tur Kod
İncelemesi", Madde 5)

## Loglama ve correlationId
Gelen her istekteki `X-Correlation-Id` header'ı, `CorrelationIdFilter`
tarafından SLF4J MDC'ye (`correlationId`) konur; header yoksa ya da
şüpheliyse (harf, rakam ve tire dışında karakter, 8–64 karakter dışında
uzunluk) yeni bir UUID üretilir. Id, o isteğe ait tüm log satırlarına
otomatik eklenir (Hibernate SQL logları dahil, bunlar `show-sql` yerine
`org.hibernate.SQL` logger'ı üzerinden yazılır) ve istek bitince MDC
temizlenir.

Customer Service'e giden Feign çağrılarında `FeignCorrelationIdInterceptor`
aynı id'yi `X-Correlation-Id` header'ı olarak iletir. Çağrı, TimeLimiter
yüzünden ayrı bir thread'de çalıştığı için `CustomerServiceExecutor`,
MDC'yi o thread'e aktarır ve iş bitince temizler.

`docker` profilinde (Compose'ta `SPRING_PROFILES_ACTIVE: docker`) loglar
JSON formatındadır ve `correlationId` ayrı bir alandır (bkz.
docs/asama8-notlar.md, "Yapılandırılmış Loglama ve correlationId").

## Performans (Cache)
Hesap sorgulama (GET /api/accounts/{id}) sonuçları Redis'te
cache'lenir. Bakiye değiştiren işlemlerde (deposit/withdraw/transfer)
ilgili hesabın cache kaydı otomatik olarak temizlenir, böylece bir
sonraki sorgu her zaman güncel veriyi yansıtır (bkz. docs/asama5-notlar.md).

## Test
Unit testler (Mockito) ve gerçek PostgreSQL üzerinde çalışan bir
Integration test (Testcontainers) mevcuttur. Sahiplik kontrolünü
doğrulayan testler de eklenmiştir (örn.
`withdraw_notOwner_throwsAccessDeniedException`,
`getAccountById_adminRole_bypassesOwnershipCheck`). Ayrıca
`CorrelationIdFilterTest` ve `FeignCorrelationIdInterceptorTest`, id
üretimini, header iletimini ve MDC temizliğini doğrular
(bkz. docs/asama6-notlar.md, docs/asama8-notlar.md).

## API Dokümantasyonu
http://localhost:8083/swagger-ui.html
Merkezi (tüm servisler): http://localhost:8080/swagger-ui.html