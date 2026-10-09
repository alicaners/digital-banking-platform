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
- **Circuit'in hangi çağrıda açıldığı (Prometheus metrikleriyle açıklandı).**
  Yapılandırma `sliding-window-size: 10`, `failure-rate-threshold: 50` ve
  `minimum-number-of-calls: 10`. Retry her denemeyi ayrı bir circuit breaker
  çağrısı olarak saydırdığı için, bir transfer (3 deneme) pencereye 3 başarısız
  çağrı ekliyor. Bu yüzden circuit, kesinti başladıktan sonra yaklaşık 5.
  başarısız denemede, yani ikinci transferin ortasında açılıyor. Önceki k6
  koşusunda "ilk üç istek 3 denemeyle tamamlandı, ilk hızlı red dördüncüde
  görüldü" gözlemi bununla tam örtüşmüyor; o koşuda metrik toplanmadığı için
  fark yeniden doğrulanamadı. Metriklerle yapılan ayrı bir koşu için bkz.
  "Metrikler ve İzleme" bölümü, "Circuit breaker deneyi".
- **136. saniyedeki 1003 ms'lik ilk başarılı transfer**, 2 başarısız deneme
  ve üçüncü denemede başarı olarak yorumlanıyor (2 × 500 ms bekleme); servis
  bu isteğin ortasında geri döndü.
- **Yavaş hata (~1000 ms) ile TimeLimiter zaman aşımı farklı şeyler.** Servis
  tamamen durduğunda bağlantı hatası anında alınır, `FeignException` olduğu
  için retry devreye girer (3 deneme + 2 × 500 ms bekleme ≈ 1000 ms); bu
  yüzden yukarıdaki yorum bu senaryo için geçerli. Servis ayakta ama yanıtı
  geç veriyorsa (soğuk başlangıç gibi) başka bir mekanizma devreye girer:
  Resilience4J'nin varsayılan 1 saniyelik TimeLimiter'ı çağrıyı keser. Bu
  durum k6 koşularında gözlenmedi, ama gerçek bir hata olarak yapılandırılmış
  loglama çalışmasında ortaya çıktı (bkz. "Yapılandırılmış Loglama ve
  correlationId" bölümü, "TimeLimiter bulgusu").



## Yapılandırılmış Loglama ve correlationId

Mikroservis mimarisinde tek bir kullanıcı isteği birden fazla servisten ve
bir Kafka mesajından geçiyor (Gateway → transaction-service → account-service
→ Kafka → notification-service). Her servisin logu ayrı bir container'da
olduğu için, bir transferin hata verdiği durumda "bu isteğin her serviste ne
yaptığını" görmek zaman damgası tahminine dayanıyordu. Bu bölümde, bir isteğin
tüm servislerde tek bir kimlikle (`correlationId`) izlenebilmesi sağlandı ve
loglar makine tarafından okunabilir (JSON) hale getirildi.

### Tasarım

- **Kimlik:** `X-Correlation-Id` HTTP header'ı. Gateway üretir: istemci geçerli
  bir değer gönderdiyse (harf, rakam ve tire, 8–64 karakter) onu kullanır,
  yoksa ya da şüpheliyse yeni bir UUID üretir. Kural kasıtlı olarak katı:
  istemciden gelen serbest metnin loglara olduğu gibi yazılması, log
  enjeksiyonuna (satır sonu karakterleriyle sahte log satırı) açık olurdu.
- **Gateway (reaktif):** Reaktif yığında bir isteğin işlenmesi tek bir
  thread'e bağlı olmadığı için MDC güvenilir değil. Bu yüzden Gateway'de id
  loga yapılandırılmış alan olarak (`StructuredArguments.keyValue`) veriliyor.
  Cevap header'ı `beforeCommit` içinde `set` ile yazılıyor; aksi halde
  downstream servis de aynı header'ı eklediğinden istemci iki değer
  görüyordu.
- **MVC servisleri (auth, customer, account, transaction):** Bir
  `OncePerRequestFilter` header'ı okuyup SLF4J MDC'ye (`correlationId`)
  koyuyor, cevaba da yazıyor ve istek bitince `finally` içinde temizliyor.
  Temizlik önemli: thread havuzundaki bir thread bir sonraki isteğe eski id
  ile gitmemeli.
- **Filtre sırası (Gateway):** CorrelationIdFilter (-3) → RateLimiterFilter
  (-2) → JwtAuthenticationFilter (-1). Böylece rate limit ya da kimlik
  doğrulama reddettiği istekler de bir id ile loglanıyor.

### JSON log

- `logstash-logback-encoder` 8.1 kullanıldı. 9.0 sürümü Jackson 3 istiyor ve
  Spring Boot 3.3.4 ile uyumsuz.
- Her serviste bir `logback-spring.xml` var: `docker` profilinde
  `LogstashEncoder` (MDC alanları otomatik JSON'a giriyor), diğer
  profillerde Spring'in normal konsol çıktısı (geliştirirken okunabilir
  kalsın diye). Docker Compose'ta her servise `SPRING_PROFILES_ACTIVE: docker`
  verildi.
- Hibernate SQL'i `show-sql` yerine `org.hibernate.SQL` logger'ı üzerinden
  yazılıyor (`show-sql: false`, logger seviyesi `debug`). `show-sql` doğrudan
  stdout'a yazdığı için JSON'a ve MDC'ye girmiyordu; logger üzerinden giden
  SQL satırları artık `correlationId` taşıyor. Parametre değerleri
  loglanmıyor.

### Servisler arası taşıma

- **Feign (HTTP):** Her serviste bir `RequestInterceptor` var; MDC'deki id'yi
  giden isteğe `X-Correlation-Id` header'ı olarak ekliyor
  (transaction → account, account → customer).
- **MDC thread sorunu:** Account ve Customer çağrıları
  `circuitBreaker.run(...)` içinde yapılıyor ve TimeLimiter bu çağrıyı
  **başka bir thread'de** çalıştırıyor. MDC thread'e özel olduğu için o
  thread'de id boştu; sadece interceptor yazmak yetmedi. `AccountServiceExecutor`
  ve `CustomerServiceExecutor`, istek thread'indeki MDC'yi kopyalayıp iş
  yapılan thread'e aktarıyor ve iş bitince eski haline döndürüyor.
- **Kafka:** Event'in içeriği değiştirilmeden, id Kafka mesajının
  **header**'ına yazılıyor (`TransactionEventProducer`). Notification-service
  consumer'ı header'ı okuyor, aynı doğrulama kuralından geçiriyor, MDC'ye
  koyup bildirimi logluyor ve `finally` içinde temizliyor. Header yoksa ya da
  şüpheliyse log satırına id yazılmıyor.

### TimeLimiter bulgusu (loglama sayesinde yakalanan gerçek hata)

Loglama çalışmasının transaction-service ayağında, servisler yeni
başladıktan sonra yapılan ilk transfer `FAILED` ("Hesap servisi şu anda
kullanılamıyor") döndü, ama Account Service'te para aslında bir saniye sonra
transfer edilmişti (kayıt `FAILED`, bakiyeler değişmiş). Aynı key ile tekrar
denemek mümkün değildi (başarısız sonuç da kayıtlı), yeni key ile ikinci
transfer normal çalıştı. Hata tekrarlanabilir çıktı: servisler yeniden
başlatılıp ~90 saniye beklendikten sonra yapılan ilk transfer aynı şekilde
başarısız oldu.

`TransactionService` catch bloklarına log eklenince stack trace nedeni
gösterdi:

```
Caused by: java.util.concurrent.TimeoutException:
TimeLimiter 'accountService' recorded a timeout exception.
```

Spring Cloud CircuitBreaker, `resilience4j.timelimiter` ayarı yapılmadıysa
varsayılan **1 saniyelik** bir TimeLimiter uyguluyor. Soğuk başlayan Account
Service'in ilk çağrısı bu süreyi aşıyor (gözlenen: ~1,1 saniye), çağrı
transaction-service tarafında kesiliyor, ama Account Service işi arka planda
tamamlıyor. Sonuç: para gitmiş, kayıt `FAILED`.

`TimeoutException` bir `FeignException` olmadığı için retry tetiklenmedi; bu
sayede aynı transfer ikinci kez işlenmedi. Sorun "çift para" değil, "yanlış
kayıt" idi.

**Düzeltme:** `transaction-service/application.yml` içine
`resilience4j.timelimiter.instances.accountService.timeout-duration: 5s`
eklendi. Aynı yapılandırma eksikliği Account Service'te
`customerService` için de vardı, o da aynı şekilde eklendi.

**Doğrulama:** Düzeltmeden sonra iki ayrı soğuk başlangıçta transfer
`COMPLETED` döndü, bakiyeler beklenen değerde kaldı.

**Kalan sınırlama:** Zaman aşımı süresi ne olursa olsun, Account Service
süreyi aşarsa aynı tutarsızlık (para gitti, kayıt `FAILED`) oluşabilir.
Kalıcı çözüm, belirsiz sonuçlarda Account Service'e idempotency key ile
durumu sorgulayıp kaydı düzelten bir mutabakat (reconciliation) adımı olurdu;
bu çalışmada yapılmadı.

### Karşılaşılan pratik sorunlar

- **Compose değişken önceliği:** Docker Compose'ta terminalde ayarlı bir
  ortam değişkeni `.env` dosyasındaki değeri ezer. Gateway testi için
  terminale geçici `JWT_SECRET` verilip temizlenmediğinde, container yanlış
  secret ile başladı ve tüm istekler 401 döndü. Test sonrası `set JWT_SECRET=`
  ile temizlemek gerekiyor.
- **Eureka gecikmesi:** Container yeniden başlayınca servisler birbirini
  30–90 saniye sonra görüyor; ilk istek bu sürede başarısız olabilir.
- **Spring Data uyarısı:** Loglarda "Serializing PageImpl instances as-is..."
  uyarısı var (sayfalama endpoint'lerinden). Çözümü JSON çıktı yapısını
  değiştireceği için bu çalışmanın kapsamı dışında bırakıldı.

### Doğrulama

Tek bir transfer (`X-Correlation-Id: transfer-test-0007`) ve
`findstr transfer-test-0007` ile dört servisin logunda aynı id görüldü:
Gateway (istek özeti, süre), transaction-service (SQL, Kafka producer),
account-service (iki select, iki update) ve notification-service
("BİLDİRİM GÖNDERİLDİ ... İşlem #311 başarıyla tamamlandı").

Birim testleri: her MVC servisinde `CorrelationIdFilterTest` (id yoksa
üretiliyor, geçerli id korunuyor, şüpheli id yenisiyle değiştiriliyor, MDC
istek sonunda temizleniyor), Gateway'de ek olarak tek header kontrolü;
`FeignCorrelationIdInterceptorTest` (transaction ve account); producer için
header ekleme testi; consumer için üç test (header var, yok, şüpheli).

### Sınırlamalar

- Eureka-server JSON log kullanmıyor.
- Kafka event'inde id yalnızca mesaj header'ında; mesajı header'ı okumayan bir
  tüketici id'yi göremez (şu an tek tüketici notification-service).
- Logların toplanması/aranması (ELK, Loki gibi) bu çalışmanın kapsamı dışında;
  loglar şimdilik `docker logs` ile okunuyor. JSON format, ileride böyle bir
  araç eklemeyi kolaylaştırıyor.


## Metrikler ve İzleme (Prometheus + Grafana)

Loglar "bir istekte ne oldu" sorusunu cevaplıyor, ama "sistem şu an genel olarak
nasıl davranıyor" sorusunu cevaplamıyor: saniyede kaç istek geliyor, devre
kesici açık mı, rate limiter kaç isteği reddetti. Bunlar zaman içinde sayılan
değerler olduğu için log yerine metrik olarak toplandı. Önceki bölümlerde
yazılan davranışlar (circuit breaker, retry, TimeLimiter, rate limiter) bu
sayede ilk kez sayılarla doğrulanabilir hale geldi.

### Mimari

- **Actuator + Micrometer:** Yedi servisin hepsi (api-gateway, auth, customer,
  account, transaction, notification, eureka-server) `micrometer-registry-prometheus`
  bağımlılığıyla `/actuator/prometheus` uç noktasında metrik yayıyor.
  `management.endpoints.web.exposure.include: health,prometheus` ile yalnızca
  bu iki uç nokta açık; `env`, `beans` gibi hassas uç noktalar kapalı.
- **Ayrı yönetim portu (9100):** Metrikler uygulama portundan değil,
  `management.server.port: 9100` ile ayrı bir porttan sunuluyor. Bu port
  `docker-compose.yml` içinde host'a **yayımlanmıyor**; yalnızca Docker ağı
  içinden erişilebiliyor. Böylece "dışarıya sadece Gateway (8080) açık" kuralı
  korunuyor ve metrikler Gateway üzerinden internete çıkmıyor.
- **Etiket:** `management.metrics.tags.application: ${spring.application.name}`
  ile her metrik hangi servisten geldiğini taşıyor.
- **Prometheus (pull modeli):** Servisler metrik göndermiyor; Prometheus her
  15 saniyede bir yedi servisin 9100 portunu okuyor (`job: banking-services`).
  Veri 7 gün saklanıyor. Host'ta yalnızca `127.0.0.1:9090` üzerinden
  erişilebilir.
- **Grafana:** `127.0.0.1:3000` üzerinde, yalnızca yerel makineden erişilebilir.
  Veri kaynağı ve dashboard **dosyadan** tanımlanıyor (provisioning): `docker/grafana/provisioning`
  altında veri kaynağı (uid `prometheus`) ve dashboard sağlayıcısı,
  `docker/grafana/dashboards/banking-overview.json` içinde dashboard'un kendisi.
  Böylece ortam sıfırdan kurulduğunda elle ayar yapmak gerekmiyor ve dashboard
  git'te sürümlenebiliyor.

### Doğrulama

Metrik portunun gerçekten dışarıya kapalı olduğu iki komutla kontrol edildi:

docker run --rm --network digital-banking-platform_banking-network curlimages/curl -s -o NUL -w “%{http_code}” http://banking-auth-service:9100/actuator/prometheus

→ `200` (Docker ağı içinden erişilebiliyor; yedi servisin hepsi için denendi)

curl http://localhost:9100/actuator/prometheus

→ `Failed to connect` (host'tan erişilemiyor)

Prometheus'un hedefler sayfasında yedi hedefin yedisi de `UP`.

### Dashboard: "Banking Platform - Genel Bakış"

Dashboard 10 saniyede bir yenileniyor ve şu panelleri içeriyor:

- **Özet kutuları:** ayakta servis sayısı (7 beklenir), Gateway istek hızı,
  rate limiter'ın verdiği HTTP 429 hızı, devre kesicilerin genel durumu
  ("Hepsi kapalı" / "AÇIK VAR").
- **Servis bazında istek hızı ve ortalama gecikme.** Prometheus'un kendi
  scrape istekleri (`/actuator/*`) `http_server_requests` içinde de sayıldığı
  için bu panellerde `uri!~"/actuator.*"` ile hariç tutuldu.
- **Devre kesici durumu** (zaman çizelgesi: Kapalı / Yarı açık / Açık), çağrı
  sonuçları (başarılı, başarısız, `not_permitted`), TimeLimiter zaman aşımları.
- **Rate limiter'da kalan izin sayısı**, JVM heap ve CPU.
- **Transfer sonuçları** (aşağıda anlatılan özel metrikten).


### Özel metrik: `banking_transfers_total`

Hazır HTTP metrikleri bir transferin **iş sonucunu** göstermiyor: transfer
başarısız olsa bile HTTP 200 dönüyor (`status: FAILED`), yani HTTP istatistiği
bu hataları görmüyor (bkz. k6 bölümündeki `http_req_failed` notu). Bu yüzden
`TransactionService` içine bir Micrometer sayacı eklendi.

- **Etiketler:** `status` (`completed` / `failed`) ve `reason`:
   - `none`: başarılı transfer
   - `rejected`: Account Service isteği iş kuralı nedeniyle reddetti (4xx,
     yetersiz bakiye gibi)
   - `unavailable`: servise ulaşılamadı ya da zaman aşımı
   - `circuit_open`: devre kesici açık olduğu için çağrı hiç yapılmadı
   - `unexpected`: beklenmeyen hata
- **Sayaç, kayıt veritabanına yazıldıktan sonra artıyor** ve yalnızca yeni
  oluşturulan işlemleri sayıyor. Aynı `Idempotency-Key` ile yapılan tekrar
  istek (replay) mevcut kaydı döndürdüğü için sayaca eklenmiyor; Postman'de
  aynı isteği tekrar göndererek doğrulandı.
- **Sayaçlar başlangıçta sıfırla kaydediliyor.** Prometheus'ta `rate()` ve
  `increase()`, bir seri ilk kez `N` değeriyle görünürse artışı sıfır sayıyor;
  yani ilk başarısız transfer hiç görünmeyebilir. Bu yüzden beş kombinasyonun
  hepsi constructor'da 0 olarak oluşturuluyor.
- **Testler:** `TransactionServiceTest` içine dört test eklendi (başarı,
  `RuntimeException` → `unavailable`, `CallNotPermittedException` →
  `circuit_open`, mevcut idempotency key'in sayacı artırmaması). Servis genelinde
  22 test geçiyor.

### Circuit breaker deneyi (metriklerle)

Kesinti k6 ile tek sanal kullanıcıdan transfer göndererek, Account Service
container'ı durdurulup tekrar başlatılarak simüle edildi. Sayılar Prometheus
sayaçlarından okundu:

| Metrik | Değer |
|---|---|
| Başarılı çağrı | 100 |
| Başarısız çağrı | 23 |
| `not_permitted` (devre açıkken reddedilen) | 53 |
| TimeLimiter zaman aşımı | 4 |

Bu sayılardan çıkan sonuçlar:

- **Retry denemeleri tek tek sayılıyor.** Pencere 10 çağrı, eşik %50, minimum 10
  çağrı olduğu için devre, kesintinin başlamasından sonra yaklaşık 5. başarısız
  denemede, yani ikinci transferin ortasında açılıyor. (Önceki k6 koşusundaki
  "ilk üç istek tam 3 denemeyle bitti" gözlemiyle birebir uyuşmuyor; o koşuda
  metrik yoktu, bu yüzden farkın nedeni yeniden doğrulanamadı.)
- **Devre açıkken her transfer tek bir `not_permitted` üretiyor,** retry
  yapılmıyor ve yanıt 20–40 ms'de dönüyor. Gerçek çağrı yapılan başarısız
  transferler ise yaklaşık 1 saniye sürüyor (3 deneme + 2 × 500 ms bekleme).
  Devre açıkken reddedilen 53 çağrı için, aksi halde en az 57 saniye bekleme
  harcanacaktı.
- **`TimeoutException` retry edilmiyor.** Durdurulan container'ın IP'sine
  bağlanmaya çalışan çağrılar takılıp kalıyor ve 5 saniyelik TimeLimiter
  tarafından kesiliyor (4 zaman aşımı).
- **HALF_OPEN durumunda 3 çağrıya izin veriliyor.** Bunlar üç ayrı transferin
  birer çağrısı olabileceği gibi, tek bir transferin 3 retry denemesi de olabiliyor.
- **Toparlanma:** Container yeniden başlatıldıktan sonra ilk başarılı transfer
  yaklaşık 74 saniye sürdü. Bunun büyük kısmı Eureka kaydı ve LoadBalancer
  önbelleğinin yenilenmesi; devre kesicinin bekleme süresi tek başına değil.


### Karşılaşılan pratik sorunlar

- **Başında boşluk olan klasör adı:** IntelliJ'de "New Directory" ile klasör
  adı `   grafana` (başında boşluk) olarak girildi. Docker, bulamadığı yolu
  host'ta boş bir klasör olarak oluşturdu ve Grafana'ya boş provisioning
  dizini bağlandı; veri kaynağı listesi boş geldi. Klasör doğru adla yeniden
  taşınıp `docker compose restart grafana` yapılınca düzeldi.
- **State-timeline ve eşik renkleri:** Devre kesici durumu panelinde renk modu
  `thresholds` iken değer eşlemesindeki metin ("Kapalı") gösterilmiyor, yerine
  "-∞+" yazıyordu. Renk modu `fixed` yapılıp renkler eşlemeye taşındı.
- **Yeni seride `rate()`:** Daha önce hiç görünmemiş bir sayaç ilk kez
  göründüğünde `rate()` sıfır döner (429 kutusu ilk döngüde 0.00 kaldı). Kutu
  `or vector(0)` ile korundu, transfer sayaçları ise başlangıçta sıfırlanarak
  kaydedildi (yukarıda).
- **Scrape istekleri de sayılıyor:** `http_server_requests` Prometheus'un kendi
  `/actuator/prometheus` isteklerini de içeriyor; panellerde hariç tutuldu.
- **Rate limiter 4xx kayıtları:** Gateway'in 429 yanıtları `status="429"`,
  `uri="UNKNOWN"` etiketiyle kaydediliyor. Ayrı bir Gateway sayacı yazmak
  yerine bu hazır metrik kullanıldı.
- **Eureka bağlantıları:** Eureka paneldeki instance'ların durum/sağlık
  bağlantıları artık 9100 portunu gösteriyor (`management.port` metadata'sı).
  Yalnızca görünüş sorunu; servis keşfi etkilenmiyor.

### Sınırlamalar

- **p95 gecikme yok:** Spring varsayılan olarak histogram kovaları yayınlamadığı
  için dashboard yalnızca ortalama gecikmeyi gösteriyor. Ortalama, nadir yavaş
  istekleri gizler; yüzdelik dilimler için histogram açılması gerekir.
- **Uyarı (alert) kuralı yok:** Metrikler toplanıyor ve görselleştiriliyor ama
  devre kesici açıldığında kimseye bildirim gitmiyor (Alertmanager yok).
- **Kısa durumlar kaçabilir:** Prometheus 15 saniyede bir örnek aldığı için kısa
  süren HALF_OPEN durumu grafikte hiç görünmeyebilir.
- **Varsayılan Grafana parolası:** `GRAFANA_ADMIN_PASSWORD` verilmezse
  `admin/admin` kullanılıyor. Grafana yalnızca `127.0.0.1`'e bağlı olduğu için
  yerel geliştirmede kabul edilebilir; ortak bir sunucuda mutlaka `.env`
  üzerinden değiştirilmeli.
- **Veri saklama:** Prometheus verisi 7 gün tutuluyor (`prometheus-data`
  volume'ü). Uzun vadeli analiz için uygun değil.
- **Dashboard'u arayüzden düzenleme:** `allowUiUpdates: false` olduğu için
  Grafana arayüzünden yapılan değişiklikler kalıcı olmuyor; değişiklik JSON
  dosyasında yapılıp commit edilmeli (dosya 30 saniyede bir yeniden okunuyor,
  restart gerekmiyor).