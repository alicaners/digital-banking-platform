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