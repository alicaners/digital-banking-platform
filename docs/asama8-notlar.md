# Aşama 8 — Hardening (Sağlamlaştırma)

## Gün 1 — Konu A: Hızlı Güvenlik Düzeltmeleri

Bir kod incelemesi (review) sonrası tespit edilen üç kritik güvenlik
açığı giderildi:

1. **JWT secret varsayılan değeri kaldırıldı**: auth-service ve
   api-gateway'de `${JWT_SECRET:varsayılan...}` yerine `${JWT_SECRET}`
   kullanıldı - artık gerçek bir .env dosyası olmadan uygulama hiç
   başlamıyor, kaynak kodda sabit bir secret kalmadı.

2. **Servis bypass'ı engellendi**: docker-compose.yml'de sadece
   api-gateway'in portu (8080) dışarı açık bırakıldı, diğer altı
   uygulama servisinin portları kaldırıldı - artık
   http://localhost:8083/api/accounts gibi doğrudan servis erişimi
   mümkün değil, her istek zorunlu olarak Gateway üzerinden (ve
   dolayısıyla JWT kontrolünden) geçiyor.

3. **docker-compose.yml ile README arasındaki tutarsızlık giderildi**:
   Her uygulama servisine `build: context:` eklendi - artık
   `docker compose up -d --build` gerçekten Dockerfile'lardan sıfırdan
   inşa edebiliyor, önceden elle `docker build` yapmak şart değil.

**Test ile doğrulandı**: Gateway üzerinden (localhost:8080) istekler
normal çalışmaya devam etti (`POST /api/auth/login` doğru hata mesajı
döndürdü). Doğrudan servis portlarına yapılan istek
(`curl http://localhost:8083/api/accounts`) "Failed to connect"
hatasıyla reddedildi - servis artık host makineden erişilemiyor,
sadece Docker network'ü içinden.

## Gün 1 — Konu B: Authorization (Yetkilendirme)

Sistemde Authentication (kimlik doğrulama) vardı ama Authorization
(yetkilendirme) yoktu - geçerli bir token'a sahip herhangi bir
kullanıcı, başka bir kullanıcının hesabını/müşteri kaydını
görebiliyor, hatta hesabından para çekebiliyordu. Bu, en kritik
güvenlik açığıydı ve tamamen giderildi.

**Yapılan değişiklikler:**

1. **JWT'ye kimlik bilgisi eklendi**: `JwtTokenProvider.generateToken()`
   artık sadece username değil, `userId` ve `role` bilgilerini de
   token'a (custom claim olarak) ekliyor.

2. **Gateway, doğrulanmış kimliği downstream servislere iletiyor**:
   `JwtAuthenticationFilter`, token'ı doğruladıktan sonra içindeki
   `userId`/`role`'ü çıkarıp, isteği `X-User-Id` ve `X-User-Role`
   header'larıyla zenginleştiriyor (`request.mutate()`). Bu header'lar
   sadece Gateway tarafından ekleniyor - client'ın kendi isteğine
   sahte bir `X-User-Id` eklemesi, Gateway'in üzerine yazdığı gerçek
   değerle geçersiz kılınıyor.

3. **Customer ve Account entity'lerine `userId` eklendi**: Her kayıt,
   artık hangi kullanıcıya ait olduğunu biliyor. Bu, User → Customer →
   Account zincirini her istekte ayrıca sorgulamak yerine, doğrudan
   Account/Customer üzerinde hızlı bir sahiplik kontrolü yapılmasını
   sağlıyor.

4. **Ownership kontrolü eklendi**: `CustomerService` ve `AccountService`'e
   `checkReadAccess`/`checkWriteAccess` metodları eklendi. Kural:
    - Görüntüleme (`getById`, `getAll`): ADMIN rolü herkesin kaydını
      görebilir, diğerleri sadece kendi kaydını.
    - Değiştirme (`update`, `delete`, `withdraw`): ADMIN dahil, sadece
      kaydın gerçek sahibi işlem yapabilir - admin'e kısıtlı (sadece
      okuma) yetki verildi, bu bilinçli bir tasarım kararı.
    - **İstisna - `deposit`**: Ownership kontrolü kasıtlı olarak
      eklenmedi, çünkü bir hesaba para yatırmak (örn. birine transfer
      yapmak), o hesabın sahibi olmayı gerektirmemeli. Sadece
      `withdraw`'da (paranın çıkışında) sahiplik zorunlu.

5. **Transaction Service güncellendi**: `TransactionController`,
   Gateway'den gelen `X-User-Id`'yi okuyup `TransactionService.transfer()`'a
   iletiyor. `AccountServiceClient` (Feign) arayüzü, `withdraw`/`deposit`
   çağrılarına `@RequestHeader("X-User-Id")` parametresi eklenerek
   güncellendi - Transaction Service, Account Service'e giderken artık
   kullanıcı kimliğini de taşıyor, aksi halde transferler Account
   Service'in yeni zorunlu header kontrolünde başarısız olurdu.

6. **403 Forbidden desteği eklendi**: Her iki serviste de yeni bir
   `AccessDeniedException` sınıfı ve `GlobalExceptionHandler`'a bir
   `@ExceptionHandler` kuralı eklendi - yetkisiz erişim denemeleri artık
   genel bir 500 hatası değil, doğru ve anlamlı bir 403 Forbidden
   döndürüyor.

**Karşılaşılan sorun**: Metod imzaları değiştiği için (userId parametresi
eklendi), Aşama 6'da yazılan Unit ve Integration testler (AccountServiceTest,
AccountServiceIntegrationTest, TransactionServiceTest, AuthServiceTest)
derleme hatası verdi ("actual and formal argument lists differ in length").
Testler, yeni imzalara uyacak şekilde güncellendi; ayrıca yeni authorization
davranışını doğrulayan ek testler eklendi (örn.
`withdraw_notOwner_throwsAccessDeniedException`,
`getAccountById_adminRole_bypassesOwnershipCheck`).

**Test ile doğrulandı (uçtan uca, gerçek senaryo)**: İki farklı kullanıcı
(register + login ile) oluşturuldu. Kullanıcı 1, kendi müşteri kaydını
ve hesabını açtı. Kullanıcı 2'nin token'ıyla Kullanıcı 1'in hesabına
erişilmeye çalışıldığında (`GET /api/accounts/1`), sistem doğru şekilde
`403 Forbidden - "Bu hesaba erişim yetkiniz yok"` döndürdü. Kullanıcı 1'in
kendi isteği (`GET /api/customers`) ise doğru şekilde sadece kendi kaydını
listeledi, sistemdeki tüm müşterileri değil.


## Gün 2 — Konu A: Atomic Transfer (Saga Pattern'in Basitleştirilmesi)

Transaction Service, önceden bir Saga/compensation pattern'i ile transfer
yapıyordu: önce sender hesabından `withdraw` (Feign ile Account Service'e
istek), başarılıysa receiver hesabına `deposit`. Deposit başarısız olursa,
`withdraw`'ı geri almak için bir "compensating transaction" (`REVERSED`
durumu) tetikleniyordu.

**Bu, gereksiz bir karmaşıklıktı ve basitleştirildi.** Saga pattern'i,
gerçek dünyada farklı veritabanlarına/servislere yayılmış işlemler için
var olan bir çözümdür - çünkü tek bir veritabanı transaction'ı ile atomiklik
garanti edilemediği durumlarda, "önce yap, olmazsa telafi et" mantığına
ihtiyaç duyulur. Ama bizim projemizde sender ve receiver hesapları **aynı
veritabanında** (`account_db`) duruyor. Bu durumda Saga kurmak, veritabanının
zaten bedava sunduğu bir garantiyi (tek bir `@Transactional` bloğunun ACID
atomikliği) elle ve hataya açık bir şekilde yeniden inşa etmek anlamına
geliyordu.

**Yapılan değişiklik**: Account Service'e yeni bir `transfer()` metodu
eklendi (`@Transactional`), hem düşüş hem artış tek bir veritabanı
transaction'ı içinde gerçekleşiyor - ya ikisi birden olur, ya hiçbiri
olmaz, bunu veritabanı seviyesinde garanti ediyoruz. Transaction Service
artık sadece bu tek endpoint'i (`POST /api/accounts/internal/transfer`)
çağırıyor, kendi başına withdraw/deposit orkestrasyonu yapmıyor.
`withdrawWithRetry`, `compensate`, `REVERSED` durumu kaldırıldı.

**Saga ne zaman hâlâ anlamlı olurdu**: Hesaplar farklı veritabanlarına/
servislere bölünseydi (örn. yurt dışı transferlerde farklı bir banka
sistemine gidiliyorsa), ya da bir adımın (örn. bildirim gönderimi) hatası
transferin kendisini geri almamalıysa - o zaman gerçek bir Saga/compensation
mekanizmasına ihtiyaç olurdu. Bizim senaryomuzda böyle bir dağıtıklık yok.

**Test ile doğrulandı**: İki hesap arasında 100 birimlik bir transfer
yapıldı, işlem sonrası iki hesabın toplam bakiyesi değişmeden (500,
400/100 olarak) korundu - atomiklik doğrulandı.

## Gün 2 — Konu B: Idempotency + Retry/Circuit Breaker Düzeltmeleri

Review'da tespit edilen iki teknik borç giderildi:

1. **Circuit Breaker yanlış isimlendirilmişti (review madde #8)**: Feign'in
   otomatik circuit breaker entegrasyonu (`spring.cloud.openfeign.circuitbreaker.enabled=true`),
   her Feign metodu için kendi ürettiği bir isimle breaker açıyordu - bu
   isim, `application.yml`'de elle tanımladığımız `accountService`
   config'iyle hiçbir zaman eşleşmiyordu. Sonuç: yazdığımız circuit
   breaker ayarları (failure threshold, wait duration vb.) sessizce hiç
   uygulanmıyordu.

2. **`@Retryable` self-invocation bug'ı (Aşama 6'dan kalma bilinen sorun)**:
   Spring'in `@Retryable` annotation'ı AOP proxy'ye dayanıyor - bir metod
   kendi sınıfı içinden çağrıldığında proxy devreye girmiyor, retry hiç
   tetiklenmiyordu.

3. **Idempotency koruması yoktu**: Aynı transfer isteği (örn. istemci
   timeout sonrası veya ağ hatası nedeniyle) iki kez gönderilirse, para
   iki kez transfer edilebilirdi.

**Yapılan değişiklikler:**

1. `Transaction` entity'sine `idempotencyKey` alanı eklendi (`unique`,
   `not null`). `TransactionController`, istemciden `Idempotency-Key`
   header'ını zorunlu istiyor - bu key **istemci tarafından üretiliyor**,
   sunucu tarafından değil, çünkü idempotency'nin amacı istemcinin "bu
   isteği daha önce gönderdim mi" diye sorabilmesidir.

2. `TransactionService.transfer()`, işleme başlamadan önce
   `findByIdempotencyKey` ile kontrol ediyor - kayıt varsa, hiçbir işlem
   yapmadan eski sonucu döndürüyor. Eş zamanlı (concurrent) aynı-key
   istekleri için, veritabanının `unique` constraint'i son güvenlik ağı
   olarak devrede (`DataIntegrityViolationException` yakalanıp mevcut
   kayıt döndürülüyor).

3. Yeni bir `AccountServiceExecutor` sınıfı yazıldı:
   programatik `RetryTemplate` (Spring Retry) + Spring Cloud'un
   `CircuitBreakerFactory`'si (açıkça `"accountService"` adıyla) birlikte
   kullanılıyor. `RetryTemplate` bir proxy/annotation mekanizmasına değil
   doğrudan çağrılan bir nesneye dayandığı için, önceki self-invocation
   bug'ı kökten ortadan kalktı.

4. Retry mantığı seçici: sadece 5xx/bağlantı hataları tekrar deneniyor
   (`.retryOn(FeignException.class)`), 4xx iş kuralı hataları (yetersiz
   bakiye, yetkisiz vb.) `NonRetryableException`'a sarılıp hiç tekrar
   denenmiyor - çünkü bu tür hatalar tekrar denense de sonuç değişmez.

5. Feign'in otomatik circuit breaker'ı (`feign.circuitbreaker.enabled`,
   `spring.cloud.openfeign.circuitbreaker.enabled`) kapatıldı, tek
   sorumluluk artık `AccountServiceExecutor`'da. `AccountServiceClient`'tan
   `fallback` attribute'u ve `AccountServiceClientFallback` sınıfı
   kaldırıldı.

**Karşılaşılan sorun**: `idempotencyKey` alanı `NOT NULL` + `UNIQUE`
olarak eklendiğinde, `transactions` tablosunda Gün 2 Konu A'dan kalma
eski test satırları olduğu için Hibernate'in `ddl-auto: update` mekanizması
bu kolonu ekleyemedi (var olan satırlar bu alanı boş bırakacağı için
constraint ihlali oluşuyordu). Tablo test verisi olduğu için `TRUNCATE
TABLE transactions` ile temizlenip servis yeniden başlatıldı, kolon
temiz tabloya sorunsuz eklendi.

**Test ile doğrulandı**: Aynı `Idempotency-Key` (`test-key-001`) ile iki
kez transfer isteği gönderildi. İlk istek `COMPLETED` durumuyla transferi
gerçekleştirdi (sender 500→450, receiver 0→50). İkinci istek, tamamen
aynı `id` ve `createdAt` değerleriyle (yani transferi tekrar çalıştırmadan)
aynı sonucu döndürdü; bakiyeler ikinci istekten sonra da değişmeden kaldı
(450/50) - idempotency doğrulandı.


## Gün 3 — Review Sonrası Bulunan Eksiklikler

Gün 2'nin tamamlanmasının ardından yapılan detaylı bir kod incelemesinde
6 madde tespit edildi (kritik: idempotency'nin kullanıcı bazında olmaması,
aynı key ile farklı transfer body'si kabul edilmesi, internal endpoint'lerin
dışarıdan erişilebilir olması; orta: self-transfer engeli olmaması, circuit
breaker/retry'ın gerçek senaryoda hiç test edilmemiş olması; düşük:
optimistic lock için dostane mesaj eksikliği). Ayrıca Gün 3 üzerinde
çalışılırken, PC yeniden başlatıldıktan sonra Kafka'nın geçici olarak
çökmesi sırasında canlı olarak 7. bir madde keşfedildi: Kafka event
yayınının başarısız olması durumunda, zaten veritabanına kaydedilmiş bir
transferin client'a yanlışlıkla `500` olarak dönmesi.

### Madde 1 — Internal Endpoint Güvenliği

`POST /api/accounts/internal/transfer` gibi, sadece servisler arası
(Transaction Service → Account Service, Eureka üzerinden) kullanılması
gereken endpoint'ler, Gateway üzerinden dışarıdan da (`http://localhost:8080/api/accounts/internal/transfer`)
çağrılabiliyordu - JWT doğrulaması bile gerekmiyordu, çünkü bu endpoint
zaten kimlik bilgisini header'dan (`X-User-Id`) okuyordu ve bu header'ı
sahte olarak elle eklemek mümkündü.

**Yapılan değişiklik**: `JwtAuthenticationFilter`'a, path'i `/internal/`
içeren her isteği (açık endpoint kontrolünden ve JWT doğrulamasından
**önce**) doğrudan `403 Forbidden` ile reddeden bir kural eklendi.

**Test ile doğrulandı**: `POST http://localhost:8080/api/accounts/internal/transfer`
hem `Authorization` header'ı ile hem de header olmadan `403 Forbidden`
döndürdü. Normal akış (Transaction Service → Account Service, Gateway'i
hiç kullanmadan Eureka/Feign ile) etkilenmeden çalışmaya devam etti.

### Madde 2 — Idempotency Key Kullanıcı Bazında Scope Edildi

`idempotencyKey` alanı önceden **tüm sistemde** tekildi (`unique = true`,
tek kolon). Bu, iki farklı kullanıcının tesadüfen aynı key string'ini
kullanması durumunda, ikinci kullanıcının isteğinin birinci kullanıcının
transfer kaydını (cache-hit olarak) görmesine yol açıyordu - bir veri
sızıntısı riski.

**Yapılan değişiklikler**: `Transaction` entity'sine `userId` alanı
eklendi; tekillik kısıtı tek kolondan `(user_id, idempotency_key)`
birleşik kısıtına çevrildi; `TransactionRepository`'de
`findByIdempotencyKey` yerine `findByIdempotencyKeyAndUserId` kullanılmaya
başlandı.

**Karşılaşılan sorun**: Hibernate `ddl-auto: update`, eski tekil kısıtı
otomatik silmediği için (sadece ekleme yapıyor, kaldırma yapmıyor), yeni
birleşik kısıt eklendikten sonra bile eski `idempotency_key` tekil kısıtı
veritabanında kalmaya devam etti. `ALTER TABLE ... DROP CONSTRAINT` ile
elle temizlendi.

**Test ile doğrulandı**: İki farklı kullanıcı, aynı `Idempotency-Key`
string'i ile bağımsız transferler yaptı - ikisi de kendi transferini
tamamladı, birbirinin kaydını görmedi. Aynı kullanıcının aynı key'i
tekrar kullanması hâlâ doğru şekilde cache'ten dönüyor.

### Madde 3 — Aynı Key, Farklı Transfer Body'si → 409 Conflict

Aynı `Idempotency-Key` ile **farklı** bir transfer body'si (farklı tutar,
farklı hesap) gönderilirse, sistem bunu fark etmeden sessizce eski
sonucu dönüyordu - idempotency'nin doğru semantiğine aykırıydı.

**Yapılan değişiklik**: Yeni bir `IdempotencyConflictException` eklendi.
Cache-hit kontrolünde (hem normal yolda hem eşzamanlı istek çakışması
yolunda), gelen isteğin `senderAccountId`/`receiverAccountId`/`amount`
alanları veritabanındaki kayıtla eşleşmiyorsa bu exception fırlatılıyor,
`GlobalExceptionHandler` bunu `409 Conflict` olarak dönüyor.

**Test ile doğrulandı**: Aynı key ile farklı `amount` gönderildiğinde
`409 Conflict` alındı; aynı key ile aynı body tekrar gönderildiğinde
normal cache-hit davranışı (regresyon yok) doğrulandı.

### Madde 4 — Gönderen = Alıcı Hesap Validasyonu

Bir kullanıcının kendi hesabından yine kendi hesabına transfer yapmasını
engelleyen bir kontrol yoktu.

**Yapılan değişiklik**: `TransactionService.transfer()`'ın en başına
(herhangi bir veritabanı sorgusu veya Account Service çağrısından önce)
`senderAccountId == receiverAccountId` kontrolü eklendi,
`IllegalArgumentException` ile `400 Bad Request` dönüyor.

**Test ile doğrulandı**: `senderAccountId` ve `receiverAccountId` aynı
gönderildiğinde `400 Bad Request` + "Gönderen ve alıcı hesap aynı olamaz"
mesajı alındı.

### Madde 5 — Circuit Breaker / Retry Gerçek Senaryo Testi

Circuit Breaker ve Retry mekanizmaları (Gün 2'de eklenmişti) daha önce
sadece unit testlerle doğrulanmıştı, gerçek bir servis kesintisi
senaryosunda hiç test edilmemişti.

**Karşılaşılan sorun**: `application.yml`'de `sliding-window-size: 10`
tanımlıyken `minimum-number-of-calls` hiç belirtilmemişti - bu da
Resilience4j'nin varsayılanı olan 100'ü devreye sokuyordu, yani circuit
breaker pratikte hiç tetiklenemeyecek şekilde yapılandırılmıştı.
`minimum-number-of-calls: 10` eklenerek düzeltildi.

**Test ile doğrulandı**: Account Service bilerek durdurulup art arda
transfer istekleri gönderildi. Gözlemlenen tam yaşam döngüsü: CLOSED
(ilk istekler yavaş, gerçek ağ çağrıları deneniyor) → OPEN (istekler
hızlı reddediliyor, gerçek çağrı yapılmıyor) → HALF-OPEN (bekleme süresi
dolunca birkaç deneme çağrısı izin veriliyor, tekrar yavaş) → tekrar OPEN
(deneme çağrıları da başarısız olunca). `AccountServiceExecutor`'ın
`RetryTemplate` içinde `CircuitBreaker.run()` çağırması nedeniyle, her
kullanıcı isteği circuit breaker'a birden fazla (retry sayısı kadar) ayrı
çağrı olarak yansıyabiliyor - bu, gözlemlenen davranışın neden bazen
"yavaş ama circuit-breaker mesajlı" bazen "hızlı ve circuit-breaker
mesajlı" olabildiğini açıklıyor, bug değil, beklenen bir tasarım detayı.
Account Service tekrar başlatıldığında sistem normale döndü (`COMPLETED`).
Ayrıca yetersiz bakiye senaryosunun (regresyon) hâlâ doğru çalıştığı
teyit edildi.

### Madde 6 — Optimistic Lock İçin Dostane Mesaj

`Account` entity'sindeki `@Version` alanı sayesinde Hibernate, eşzamanlı
güncelleme çakışmalarını (`ObjectOptimisticLockingFailureException`)
otomatik olarak tespit ediyordu, ama bu exception hiç yakalanmıyordu -
client'a muhtemelen çirkin bir `500` dönüyordu.

**Yapılan değişiklik**: Account Service'in `GlobalExceptionHandler`'ına
bu exception için bir handler eklendi, `409 Conflict` +
"Bu hesap üzerinde eşzamanlı bir işlem gerçekleşti, lütfen tekrar deneyin"
mesajı dönüyor. `AccountService.java`'da kod değişikliği gerekmedi -
exception zaten Hibernate tarafından otomatik fırlatılıyor.

**Not**: Gerçek eşzamanlılık, Postman ile elle tetiklenemedi (network
timing'ine bağlı, manuel testte garanti edilemiyor) - kodun doğruluğu
statik incelemeyle teyit edildi, gerçek yük testi (JMeter/k6) bu
projenin kapsamı dışında bırakıldı.

### Madde 7 — Kafka Event Yayını Başarısızlığının İzole Edilmesi

PC yeniden başlatıldıktan sonra Kafka container'ının geçici olarak
çökmesi sırasında canlı olarak keşfedildi: `TransactionService.transfer()`
içinde `eventProducer.publish(...)` çağrısı `try/catch` içinde değildi.
Kafka ayakta değilken, transfer zaten veritabanına başarıyla
kaydedilmiş olmasına rağmen, Kafka'ya yayın denemesi patlayınca
client'a yanlışlıkla `500 Internal Server Error` dönüyordu.

**Madde 7.1 — Yayın hatasının izole edilmesi**: `eventProducer.publish(...)`
çağrısı kendi `try/catch` bloğuna alındı; hata sadece loglanıyor
(`log.error(...)`), client'a yansımıyor - transfer sonucu (DB'de zaten
kayıtlı olan gerçek durum) her koşulda doğru şekilde dönüyor.

**Madde 7.2 — `failureReason`'ın kalıcı saklanması**: Ayrı bir eksiklik
olarak, `failureReason` sadece `transfer()` metodunun local bir
değişkeniydi, `Transaction` entity'sinde saklanmıyordu - bu yüzden aynı
`Idempotency-Key` ile tekrar istek geldiğinde (cache-hit), önceki
başarısızlık nedeni kayboluyor, `failureReason: null` dönüyordu.
`Transaction` entity'sine `failureReason` kolonu eklendi, artık her
`catch` bloğunda doğrudan entity'ye yazılıyor ve `toResponse(...)`
entity'den okuyor - hem ilk istekte hem cache-hit'te tutarlı.

**Test ile doğrulandı**: Kafka bilerek durdurulup transfer denendi -
`500` yerine `200 OK` + `COMPLETED` alındı, hata sadece loglandı
(client'a yansımadı). Ayrıca bilerek `FAILED` olan bir transfer, aynı
key ile tekrar istendiğinde `failureReason`'ın artık ikinci seferde de
doğru geldiği (`null` değil) doğrulandı.

---

Gün 3'ün tüm maddeleri, ilgili unit testlerle birlikte (her madde için
ayrı commit halinde) tamamlandı ve CI'da doğrulandı.


## Gün 4 — Kod Kalitesi, Dayanıklılık ve Sayfalama

Gün 3'te tespit edilen eksikliklerin giderilmesinin ardından, bu gün
planlı altı maddelik bir "hardening" (sağlamlaştırma) çalışması yapıldı:
domain validasyonları, Docker healthcheck'leri, rate limiter
yapılandırması, constructor injection'a geçiş, magic string'lerin
enum'a çevrilmesi ve listeleme endpoint'lerine sayfalama eklenmesi.

### Madde 1 — Domain Validasyonları

Account Service'de `openAccount()` çağrısı, verilen `customerId`'nin
gerçekten var olup olmadığını hiç kontrol etmiyordu - olmayan bir
müşteri ID'siyle de hesap açılabiliyordu. Ayrıca "kayıt bulunamadı"
durumları genel `IllegalArgumentException` ile karşılanıyordu, bu da
yanlış bir HTTP status'e (400) yol açıyordu; doğrusu 404 Not Found'du.

**Yapılan değişiklikler:**

1. Account Service'e, Customer Service'i Feign ile çağıran
   `CustomerServiceClient` eklendi (`GET /api/customers/internal/{id}`).
   Customer Service'e, sadece varlık kontrolü yapan yeni bir
   `checkCustomerExists` endpoint'i eklendi.
2. `openAccount()`, hesap açılmadan önce bu kontrolü yapıyor; müşteri
   yoksa yeni eklenen `ResourceNotFoundException` fırlatılıyor.
3. Ayrı bir `ResourceNotFoundException` sınıfı oluşturuldu,
   `GlobalExceptionHandler`'a `404 Not Found` döndüren bir handler
   eklendi. "Kayıt bulunamadı" tipindeki tüm durumlar (hesap, gönderen,
   alıcı bulunamadı vb. - toplam 5 yer) `IllegalArgumentException`'dan
   bu yeni exception'a çevrildi; gerçek iş kuralı hataları (yetersiz
   bakiye, negatif tutar vb.) `IllegalArgumentException` (400) olarak
   kaldı.

**Karşılaşılan sorun**: `AccountServiceIntegrationTest`, exception tipi
değişikliği sonrası `ApplicationContext` yüklenirken başarısız oldu.
Kök neden araştırması iki farklı, birbiriyle ilgisiz sorunu ortaya
çıkardı: (1) `src/test/resources/application.yml`'de elle yazılmış
`driver-class-name: org.h2.Driver`, Testcontainers'ın gerçek Postgres
bağlantı adresiyle çakışıyordu - `@DynamicPropertySource`'a
`driver-class-name` override'ı eklenerek düzeltildi. (2) Yeni eklenen
gerçek `CustomerServiceClient`, tam Spring context'inde Eureka/Customer
Service olmadan çağrılmaya çalışılıyordu - `@MockBean` ile mock'lanarak
düzeltildi. İkisi de Madde 1'in kendisiyle ilgisizdi, ayrı "bonus fix"
commit'leri olarak düzeltildi.

**Test ile doğrulandı**: Var olmayan bir `customerId` ile hesap açma
denemesi `404 Not Found` döndürdü. Tüm unit ve integration testler
(11/11) geçti.

### Madde 2 — Docker Healthcheck'leri

`docker-compose.yml`'de altyapı servislerinin (Postgres, Redis,
Zookeeper, Kafka) sağlık durumu kontrol edilmiyordu; uygulama servisleri
bu servislerin sadece **başlamış** (started) olmasını bekliyordu, gerçekten
**hazır** (healthy - örn. Postgres'in bağlantı kabul etmeye başlamış)
olmasını değil. Bu, özellikle ilk `docker compose up` çalıştırmasında,
uygulama servislerinin arka planındaki veritabanı henüz hazır olmadan
başlayıp bağlantı hatası vermesine yol açabiliyordu.

**Yapılan değişiklik**: Postgres (`pg_isready`), Redis (`redis-cli ping`),
Zookeeper ve Kafka (Confluent'in `cub` - Confluent Utility Belt - aracı)
için `healthcheck` blokları eklendi. Tüm uygulama servislerinin
`depends_on` tanımları `condition: service_started`'tan
`condition: service_healthy`'ye çevrildi. Ayrıca artık kullanılmayan
`version: '3.8'` satırı kaldırıldı.

**Test ile doğrulandı**: `docker compose up -d` sonrası `docker ps`
çıktısında altyapı servislerinin `(healthy)` etiketiyle göründüğü,
uygulama servislerinin bu servisler hazır olana kadar beklediği
gözlemlendi.

### Madde 3 — Rate Limiter'ın application.yml'den Okunması

API Gateway'deki `RateLimiterFilter`, `application.yml`'de tanımlı
`resilience4j.ratelimiter.instances.globalRateLimiter` ayarlarını
(limit, yenileme süresi vb.) hiç okumuyordu - filtre, bu yapılandırmadan
bağımsız çalışıyordu, yani yml'deki değerler sessizce hiç uygulanmıyordu.

**Yapılan değişiklik**: `RateLimiterFilter`, `RateLimiterRegistry`'yi
constructor injection ile alacak, `rateLimiterRegistry.rateLimiter("globalRateLimiter")`
ile yml'deki `globalRateLimiter` konfigürasyonuna bağlı gerçek bir
`RateLimiter` nesnesi üretecek şekilde güncellendi. `acquirePermission()`
`false` dönerse `429 Too Many Requests` döndürülüyor.

**Test ile doğrulandı**: `application.yml`'deki `limit-for-period: 10`
ayarıyla art arda istekler gönderildi - ilk birkaç istek `200 OK`
döndü, limit aşılınca `429 Too Many Requests` alındı, yenileme
süresinin ardından tekrar `200 OK`'e dönüldü.

### Madde 4 — Constructor Injection'a Geçiş

Tüm servislerdeki controller/service sınıfları, alan (field) seviyesinde
`@Autowired` kullanıyordu. Bu, test edilebilirliği zorlaştıran ve Spring
ekosisteminde artık önerilmeyen bir pattern - constructor injection,
bağımlılıkların `final` ve zorunlu olmasını garanti eder, dairesel
bağımlılıkları derleme zamanında yakalar ve mock'lamayı kolaylaştırır.

**Yapılan değişiklik**: Beş servisteki (customer, account, auth,
transaction servisleri + auth-service'in `JwtTokenProvider`'ı) tüm
`@Autowired` alanlar, `private final` alanlar + constructor'a çevrildi.
Spring, tek constructor'ı olan sınıflarda `@Autowired` annotation'ına
ihtiyaç duymadan otomatik olarak bu constructor'ı kullanıyor.
`JwtTokenProvider`'daki `@Value` alan injection'ı da constructor
parametre injection'ına çevrildi.

**Karşılaşılan sorunlar (üç adet, hepsi ilgisiz "bonus fix")**:
1. `AccountServiceTest`'te, Madde 1'den kalma bir test hâlâ eski
   `IllegalArgumentException`'ı bekliyordu - `ResourceNotFoundException`'a
   güncellendi.
2. Türkçe işletim sistemi locale'inde çalışan JVM'de, Kafka'nın kendi
   iç kodundaki `toUpperCase()` çağrısı "İ" (noktalı büyük I) üretip
   `CLASSİC` gibi geçersiz bir enum değeri oluşturuyor, embedded Kafka
   testini patlatıyordu. `transaction-service/pom.xml`'e
   `maven-surefire-plugin` üzerinden `-Duser.language=en -Duser.country=US`
   argLine'ı eklenerek, sadece test JVM'inin case-conversion kuralları
   İngilizce'ye zorlandı (uygulamanın kendi Türkçe davranışı/metinleri
   etkilenmedi, sadece bu üçüncü parti kütüphane hatası bypass edildi).
3. (Madde 1'in kendi bonus fix'leri - yukarıda anlatıldı.)

**Test ile doğrulandı**: Her servis için `mvnw test` çalıştırıldı, tüm
testler (account: 11, customer: 1, auth: 7, transaction: 11) geçti.

### Madde 5 — Magic String'lerin Enum'a Çevrilmesi

`status` ve `role` gibi alanlar, tüm serviste `String` olarak tutuluyor
ve `"ACTIVE"`, `"ADMIN"` gibi düz metin karşılaştırmalarıyla kontrol
ediliyordu - yazım hatalarına açık, derleyici tarafından denetlenemeyen
bir yaklaşımdı.

**Madde 5.1 — Transaction Service**: `Transaction.status` alanı
`String`'den yeni `TransactionStatus` enum'una (`PENDING`, `COMPLETED`,
`FAILED`) çevrildi, `@Enumerated(EnumType.STRING)` ile DB'de yine
okunabilir string olarak saklanması sağlandı. Kafka event payload'ı
(`TransactionEvent.status`) geriye dönük uyumluluk için `String` olarak
bırakıldı, yayınlarken `transaction.getStatus().name()` kullanıldı.

**Madde 5.2 — Account Service**: `Account.status` alanı yeni
`AccountStatus` enum'una (sadece `ACTIVE` - kod şu an başka bir değer
üretmiyor) çevrildi. `transfer()`'daki `"ACTIVE".equals(...)`
karşılaştırmaları `!= AccountStatus.ACTIVE` şeklinde enum
karşılaştırmasına çevrildi.

**Madde 5.3 — Auth Service (Role tanımı)**: `User.role` alanı yeni
`Role` enum'una (`CUSTOMER`, `ADMIN`) çevrildi. `JwtTokenProvider`
kasıtlı olarak değiştirilmedi - JWT claim'i hâlâ `String`, `AuthService`
token üretirken `user.getRole().name()` ile enum'u string'e çeviriyor.

**Madde 5.4 ve 5.5 — Customer/Account Service (Role kullanımı)**: Her
iki serviste de `"ADMIN".equals(role)` karşılaştırmaları
`Role.valueOf(role) == Role.ADMIN` şeklinde değiştirildi. `role`
parametresi hâlâ Gateway'den gelen bir `String` header (HTTP
header'ları her zaman string'dir), ama artık karşılaştırma anında
enum'a çevrilip tip güvenli şekilde kontrol ediliyor. Bilinçli bir
tasarım kararı: beklenmedik bir `role` değeri gelirse (`Role.valueOf`
başarısız olursa) sistem sessizce "yetkisiz" varsaymak yerine `400 Bad
Request` ile açıkça hata veriyor - `role` header'ı Gateway'in kendi
doğruladığı JWT'den türediği için, geçersiz bir değer aslında bir
bug/veri bütünlüğü sorunu işaretidir, sessizce yutulmamalı.

**Test ile doğrulandı**: Her alt madde için ilgili servisin testleri
çalıştırıldı, Docker'da yeniden build edilip Postman ile uçtan uca
doğrulandı (enum'ların DB'de string olarak saklandığı, ADMIN/CUSTOMER
rol ayrımının doğru çalıştığı `psql` sorgularıyla ve gerçek isteklerle
teyit edildi).

### Madde 6 — Listeleme Endpoint'lerine Sayfalama

`GET /api/customers` ve `GET /api/accounts` endpoint'leri, DB'deki
**tüm kayıtları** tek seferde dönüyordu; CUSTOMER rolü için filtreleme
de tüm kayıtları çekip Java tarafında `Stream.filter()` ile yapılıyordu
- kayıt sayısı arttıkça hem performans hem gereksiz veri transferi
  sorunu yaratacak bir yaklaşımdı.

**Yapılan değişiklikler:**

1. `CustomerRepository` ve `AccountRepository`'ye, Spring Data JPA'nın
   otomatik ürettiği `findByUserId(Long userId, Pageable pageable)`
   metodları eklendi - DB seviyesinde filtrelenmiş ve sayfalanmış sorgu.
2. `getAllCustomers()`/`getAllAccounts()` servis metodları, `Pageable`
   parametresi alıp `Page<X>` dönecek şekilde güncellendi. ADMIN dalı
   `repository.findAll(pageable)`, CUSTOMER dalı yeni
   `findByUserId(userId, pageable)` kullanıyor - `Stream.filter()`
   tamamen kaldırıldı.
3. Controller metodları `@PageableDefault(size = 20) Pageable pageable`
   parametresi alıyor; istemci `?page=0&size=10&sort=firstName,asc`
   gibi query parametreleriyle sayfa numarası, boyutu ve sıralama
   belirleyebiliyor.

**Test ile doğrulandı**: CUSTOMER rolüyle istek atıldığında sadece
kendi kayıtlarını içeren, doğru sayfalama metadata'sına (`totalElements`,
`totalPages` vb.) sahip bir `Page` objesi döndüğü doğrulandı. ADMIN
rolüyle küçük bir `size` (`?page=0&size=2` / `?page=0&size=3`)
verildiğinde, toplam kayıt/sayfa sayısının ve sayfa içeriğinin doğru
hesaplandığı (`customer-service`: 6 kayıt/3 sayfa, `account-service`:
9 kayıt/3 sayfa) teyit edildi.

---

Gün 4'ün tüm maddeleri, ilgili unit testlerle birlikte (her madde için
ayrı commit halinde) tamamlandı ve Docker/Postman ile uçtan uca
doğrulandı.