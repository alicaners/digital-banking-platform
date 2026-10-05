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
statik incelemeyle teyit edildi. Yük testi bu aşamada yapılmadı; sonradan
eklenen k6 senaryoları ("Yük Testi (k6)" bölümü) tek sanal kullanıcıyla
çalıştığı için bu çakışmayı da tetiklemiyor, optimistic lock davranışı
hâlâ yalnızca statik incelemeyle doğrulanmış durumda.

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



## Ek Düzeltmeler — İkinci Tur Kod İncelemesi

Gün 4'ün tamamlanmasının ardından, tüm proje üzerinde uçtan uca yeni bir
kod incelemesi yapıldı ve 10 madde tespit edildi. Kritik/orta öncelikli
5 madde (#1, #2, #3, #5, #8 — inceleme raporundaki orijinal numaralarıyla)
bu oturumda giderildi. Kalan maddeler (#4 - rate limiter'ın kullanıcı
bazlı olmaması; #6, #7, #10 - IBAN çakışma ihtimali, email'in büyük/küçük
harfe duyarlı olması, `JwtAuthenticationFilter`'da `startsWith` kullanımı)
bilgilendirme amaçlı not edildi, düşük öncelikli/kapsam dışı bırakıldı.

### Madde 1 — Circuit Breaker, İş Kuralı Hatalarını Arıza Saymasın

Resilience4j Circuit Breaker, varsayılan olarak `circuitBreaker.run(...)`
içinde fırlatılan **her** exception'ı (hem gerçek altyapı arızalarını hem
de normal iş kuralı hatalarını) başarısızlık istatistiğine sayıyordu.
`NonRetryableException` (yetersiz bakiye gibi legal 4xx hataları saran)
bu istatistiğe dahil oluyordu - yani sık karşılaşılan, tamamen normal bir
kullanıcı hatası (örn. çok sayıda "yetersiz bakiye" denemesi), gerçekte
Account Service çökmemişken bile devrenin (circuit) yanlışlıkla `OPEN`
duruma geçmesine yol açabilirdi.

**Yapılan değişiklik**: `transaction-service/application.yml`'deki
`resilience4j.circuitbreaker.instances.accountService` altına
`ignore-exceptions: com.banking.transaction.exception.NonRetryableException`
eklendi - artık sadece gerçek altyapı hataları (5xx, bağlantı sorunu)
circuit breaker istatistiğine sayılıyor.

**Test ile doğrulandı**: Bilinçli olarak bakiyeyi aşan tutarda, art arda
12 transfer isteği (PowerShell script ile, her biri benzersiz
`Idempotency-Key` ile) gönderildi - hepsi doğru şekilde "Yetersiz bakiye"
hatası döndürdü, hiçbirinde circuit breaker'ın yanlışlıkla devreye girip
`CallNotPermittedException` fallback mesajı dönmediği teyit edildi.

### Madde 2 — Veritabanı Kimlik Bilgilerinin Ortam Değişkenine Taşınması

`JWT_SECRET` zaten `.env` üzerinden yönetiliyordu, ama veritabanı
kullanıcı adı/şifresi (`banking_user`/`banking_pass`) hem
`docker-compose.yml`'de hem her servisin `application.yml`'inde sabit
(hardcoded) olarak yazılıydı - güvenlik açısından tutarsız bir durumdu.

**Yapılan değişiklik**: `auth-service`, `customer-service`,
`account-service`, `transaction-service`'in `application.yml`'lerinde
`username`/`password` alanları `${DB_USERNAME:banking_user}` /
`${DB_PASSWORD:banking_pass}` şeklinde ortam değişkenine çevrildi (yerel
geliştirme için eski değerler varsayılan olarak korundu). `docker-compose.yml`'de
Postgres servisinin `POSTGRES_USER`/`POSTGRES_PASSWORD`'ü ve dört uygulama
servisinin `DB_USERNAME`/`DB_PASSWORD` ortam değişkenleri `${DB_USERNAME:-banking_user}`
/ `${DB_PASSWORD:-banking_pass}` olarak `.env`'den okunacak şekilde
güncellendi. `.env.example`'a `DB_USERNAME`/`DB_PASSWORD` örnek satırları
eklendi.

**Not**: Postgres'in resmi Docker image'ı, `POSTGRES_USER`/`POSTGRES_PASSWORD`
değerlerini sadece veri dizini (`postgres-data` volume'u) ilk kez
oluşturulduğunda uyguluyor. Mevcut volume zaten eski değerlerle
oluşturulmuş olduğundan (ve yeni varsayılan değerler eskileriyle aynı
olduğundan) bir volume sıfırlamasına gerek kalmadı; ileride gerçekten
farklı bir şifreye geçilmek istenirse, ya volume silinip yeniden
oluşturulmalı ya da `ALTER USER` ile elle değiştirilmeli.

**Test ile doğrulandı**: `docker compose up -d --build` ile tüm sistem
yeniden build edildi, 4 servis de crash olmadan ayağa kalktı. Postman
üzerinden uçtan uca (register → login → customer oluşturma/listeleme →
hesap açma/yatırma → transfer) tüm akış, dört servisin de yeni ortam
değişkenleriyle Postgres'e sorunsuz bağlandığı doğrulanarak test edildi.

### Madde 3 — Constructor Injection Tutarlılığı

Gün 4 Madde 4'te çoğu servis constructor injection'a çevrilmişti, ama
üç sınıf gözden kaçmıştı: `api-gateway`'deki `JwtAuthenticationFilter`
(`@Autowired` alan injection) ve `JwtValidator` (`@Value` alan
injection), ve `transaction-service`'teki `TransactionEventProducer`
(`@Autowired` alan injection).

**Yapılan değişiklik**: Üçü de aynı desenle `private final` alan +
constructor parametresine çevrildi. `JwtValidator`'da `@Value("${jwt.secret}")`
artık constructor parametresi üzerinde.

**Test ile doğrulandı**: `api-gateway` ve `transaction-service` Docker'da
yeniden build edildi, JWT doğrulama zinciri (login + korumalı endpoint
erişimi) ve bir transfer isteği (Kafka event yayınının hâlâ çalıştığını
kanıtlamak için) uçtan uca test edildi, regresyon yok.

### Madde 4 — Eureka Server İçin Docker Healthcheck

Gün 4 Madde 2'de altyapı servislerine (Postgres, Redis, Kafka, Zookeeper)
healthcheck eklenmişti, ama `eureka-server`'a eklenmemişti - ona bağımlı
6 servis sadece `condition: service_started` kullanıyordu, yani Eureka
container'ı "başladı" sayılır sayılmaz diğer servisler başlamaya
çalışıyordu, embedded Tomcat tam hazır olmasa bile.

**Yapılan değişiklik**: `eureka-server`'da Actuator bağımlılığı ve
`curl`/`wget` olmadığı için, bash'in `/dev/tcp` özelliğiyle basit bir
port-erişilebilirlik kontrolü eklendi
(`bash -c 'echo > /dev/tcp/localhost/8761'`) - ekstra bağımlılık veya
imaj değişikliği gerektirmeyen, hafif bir çözüm. Bağımlı 6 servisin
(`api-gateway`, `auth-service`, `customer-service`, `account-service`,
`transaction-service`, `notification-service`) `depends_on` şartları
`condition: service_healthy`'ye çevrildi.

**Test ile doğrulandı**: `docker compose down` + `up -d --build` ile
sıfırdan test edildi - `banking-eureka-server` `(healthy)` durumuna
geçene kadar diğer servislerin beklediği gözlemlendi.

### Madde 5 — Account Service → Customer Service Çağrısına Dayanıklılık

`account-service`'in `openAccount()` akışında `customer-service`'e yapılan
Feign çağrısı (`checkCustomerExists`), `transaction-service`'in Account
Service'e yaptığı çağrının aksine, hiçbir circuit breaker/retry koruması
olmadan doğrudan yapılıyordu - Customer Service geçici olarak yavaşlarsa/
kesintiye uğrarsa, Account Service de hemen hata verirdi.

**Yapılan değişiklik**: Madde 1'deki (Gün 2 Konu B) desenin birebir aynısı
uygulandı: yeni bir `CustomerServiceExecutor` sınıfı (`RetryTemplate` +
`CircuitBreakerFactory`, açıkça `customerService` adıyla) ve
`NonRetryableException` eklendi. 4xx hatalar (örn. müşteri bulunamadı)
retry edilmiyor ve circuit breaker istatistiğine sayılmıyor
(`ignore-exceptions`); 5xx/bağlantı hataları en fazla 3 kez tekrar
deneniyor. `AccountService.openAccount()`, bu executor üzerinden çağrı
yapacak şekilde güncellendi; `404 Not Found` davranışı (müşteri
bulunamadığında) korunuyor.

**Test ile doğrulandı**: Geçerli bir `customerId` ile hesap açma `200 OK`
döndü (executor üzerinden geçen çağrı sorunsuz); olmayan bir `customerId`
ile `404 Not Found` + "Belirtilen müşteri bulunamadı" alındı (hata
sınıflandırmasının eskisiyle birebir aynı davrandığı doğrulandı). `mvnw test`
11/11 geçti.

---

Bu ek düzeltmelerin tamamı, ilgili maddeler için ayrı commit halinde
tamamlandı ve Docker/Postman ile uçtan uca doğrulandı.




## Yük Testi (k6)

Önceki incelemelerde "yük testi yok, circuit breaker gerçek trafikte test
edilmedi" eksikliği not edilmişti. Bunu kapatmak için `load-tests/` altına iki
k6 senaryosu eklendi (çalıştırma ayrıntıları için bkz. `load-tests/README.md`,
örnek çıktılar için `load-tests/results/`). Her senaryo kendi test verisini
(kullanıcı, müşteri, iki hesap, bakiye) Gateway üzerinden kurduğu için tekrar
tekrar çalıştırılabiliyor; iki senaryonun ortak kodu `lib/common.js`'te.

### Senaryo 1 — Taban çizgisi (`transfer-load-test.js`)

Tek sanal kullanıcı, 1 dakika, yaklaşık 1.2 saniye arayla art arda transfer.
Eşikler: transfer p95 < 500 ms, transfer hata oranı < %1, check başarısı > %99.

**Sonuç** (`results/transfer-baseline.txt`): 48 transferin tamamı `COMPLETED`,
transfer p95 = 70.3 ms, hata oranı %0, tüm eşikler geçti. Script ortak koda
(`lib/common.js`) taşındıktan sonra yeniden çalıştırıldığında aynı sonuçlar
alındı (p95 = 64.32 ms).

### Senaryo 2 — Gerçek servis kesintisinde circuit breaker (`circuit-breaker-load-test.js`)

Gün 3 Madde 5'te circuit breaker elle (Postman ile) test edilmişti. Bu
senaryoda aynı davranış, sürekli akan trafik altında zaman damgalı olarak
kaydedildi: test sürerken `banking-account-service` container'ı durdurulup
yaklaşık 2 dakika sonra yeniden başlatıldı. Her transferin süresi, devre
durumunu dolaylı olarak gösteriyor: ~1000 ms'lik hata, 3 deneme ve araya
giren 2 × 500 ms bekleme (gerçek çağrı yapılıyor, CLOSED ya da HALF-OPEN);
20–80 ms'lik hata, çağrı yapılmadan hızlı reddedilme (OPEN).

**Gözlenen akış** (`results/circuit-breaker-run.txt`):

| Zaman | Gözlem | Anlamı |
|---|---|---|
| 0–20 sn | 17 başarılı transfer, 40–125 ms | CLOSED, normal çalışma |
| 23–27 sn | 3 yavaş hata (~1.0–1.1 sn) | Servis kapalı, deneme + bekleme |
| 28 sn | İlk hızlı red (40 ms) | Circuit OPEN |
| 39–134 sn | Tekrarlayan döngüler: 8–10 hızlı red (21–79 ms), ardından 1–3 yavaş hata (~1.0 sn) | OPEN → bekleme süresi dolunca HALF-OPEN deneme çağrıları → başarısız olunca yine OPEN |
| 136 sn | İlk başarılı transfer 1003 ms, ardından 14 transfer 40–65 ms | Servis geri döndü, circuit kapandı |

Test 2 dakika 39 saniyede (4 dakikalık plandan önce) bilerek durduruldu,
çünkü kurtarma gözlendikten sonra yeni bilgi beklenmiyordu. Toplam 110
transfer: 31 `COMPLETED`, 79 `FAILED` (hızlı red ve yavaş hatalar dahil).
İlk üç döngü 16–17 saniye sürdü (HALF-OPEN'da 3 yavaş deneme); sonraki
döngüler 11–12 saniye sürdü (HALF-OPEN'da 1 yavaş deneme). Hızlı reddedilen
isteklerin süresi (21–79 ms), gerçek çağrı yapılan isteklerin süresinden
(~1000 ms) belirgin biçimde kısa: circuit breaker, kesinti sırasında
Account Service'e boşuna yük bindirmeyip istemciye hızlı hata dönüyor.

### Okunması gereken notlar ve sınırlamalar

- **`http_req_failed` neden %0 çıkıyor?** Transfer iş hataları (servise
  ulaşılamaması dahil) HTTP 200 ve `status: FAILED` olarak dönüyor, HTTP hatası
  değil. Bu yüzden başarısızlıklar k6'nın hata oranı yerine, senaryoda
  tanımlanan `transfers_completed` / `transfers_failed` sayaçlarından izleniyor.
- **Bu testler gerçek eşzamanlı yük değil.** İkisi de tek sanal kullanıcıyla,
  istekler arasında bekleyerek çalışıyor, çünkü Gateway'deki global rate
  limiter (10 saniyede 10 istek) daha hızlı bir testte iş mantığı yerine 429
  yanıtlarını ölçerdi. Yani bunlar bir kapasite/dayanıklılık-sınırı testi
  değil; normal hızda gecikmeyi ve kesinti davranışını doğruluyor. Çok
  kullanıcılı trafik altındaki davranış (örn. optimistic lock çakışmaları)
  hâlâ test edilmedi.
- **Açık nokta: circuit'in hangi çağrıda açıldığı.** Yapılandırma
  `sliding-window-size: 10`, `failure-rate-threshold: 50` ve her deneme ayrı
  bir çağrı olarak sayıldığına göre, 17 başarılı çağrıdan sonra circuit'in
  yaklaşık 5 başarısız çağrıda açılması beklenirdi. Oysa gözlemde, ilk üç
  isteğin üçü de tam 3 denemeyle tamamlandı ve ilk hızlı red dördüncü istekte
  görüldü. Bu fark şimdilik açıklanmadı; çağrı sayımı Prometheus/Grafana
  metrikleri eklendiğinde (circuit breaker durumu ve çağrı sayıları) ayrıca
  incelenecek.
- **136. saniyedeki 1003 ms'lik ilk başarılı transfer**, 2 başarısız deneme
  ve üçüncü denemede başarı olarak yorumlanıyor (2 × 500 ms bekleme); servis
  bu isteğin ortasında geri döndü.