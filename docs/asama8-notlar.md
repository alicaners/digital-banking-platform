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