# Account Service

Müşteri hesaplarının açıldığı ve bakiye işlemlerinin yönetildiği servis.

## Çalıştırma
mvnw spring-boot:run

## Port
8083

## Endpoint'ler (Gateway üzerinden JWT token gerektirir)
POST /api/accounts - Yeni hesap aç
GET /api/accounts/{id} - Hesap bilgisi ve bakiye sorgula (sahiplik kontrolü var)
GET /api/accounts - Kendi hesaplarını listele (ADMIN tüm hesapları görür)
POST /api/accounts/{id}/deposit - Hesaba para yatır (sahiplik kontrolü yok - kasıtlı)
POST /api/accounts/{id}/withdraw - Hesaptan para çek (sahiplik kontrolü var)
POST /api/accounts/internal/transfer - İki hesap arasında atomic transfer
(yalnızca Transaction Service tarafından, Feign üzerinden çağrılır;
sender hesabında sahiplik kontrolü var)

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

## Atomic Transfer (Aşama 8'de eklendi)
`internal/transfer` endpoint'i, sender ve receiver hesapları arasındaki
para transferini tek bir `@Transactional` veritabanı işlemi içinde
gerçekleştirir - ikisi de aynı veritabanında olduğu için, düşüş ve
artış ya birlikte başarılı olur ya da hiçbiri gerçekleşmez. Bu, önceden
Transaction Service'te bir Saga pattern'iyle yönetiliyordu; aynı
veritabanına sahip olmaları nedeniyle bu basitleştirmeye gidildi
(bkz. docs/asama8-notlar.md, "Gün 2 — Konu A").

## Teknik Notlar

**Para miktarları**: Tüm bakiye alanları `BigDecimal` ile tutulur,
`double`/`float` kullanılmaz — ondalık yuvarlama hatalarının önüne
geçmek için.

**Eşzamanlılık**: `Account` entity'sinde `@Version` alanı ile
optimistic locking uygulanır. Kod seviyesinde doğrulandı; gerçek
eşzamanlı çakışma senaryosu Postman ile manuel test edilemedi
(bkz. docs/asama3-notlar.md).

**IBAN üretimi (basitleştirilmiş)**: Bu projede IBAN'lar, gerçek
ISO 7064 (MOD 97-10) checksum algoritması ve resmi banka kodları
kullanılmadan, formatça gerçekçi görünen rastgele sayılarla üretilir.
Gerçek bir bankacılık sisteminde bu, merkez bankası tarafından
sağlanan resmi kod listeleri ve checksum doğrulaması gerektirir —
bu proje kapsamında bilinçli olarak basitleştirilmiştir.

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
`getAccountById_adminRole_bypassesOwnershipCheck`)
(bkz. docs/asama6-notlar.md, docs/asama8-notlar.md).

## API Dokümantasyonu
http://localhost:8083/swagger-ui.html
Merkezi (tüm servisler): http://localhost:8080/swagger-ui.html