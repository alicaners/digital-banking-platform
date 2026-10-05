# Customer Service

Müşteri kayıtlarının yönetildiği servis. Oluşturma, sorgulama,
güncelleme ve silme (CRUD) işlemlerini sağlar.

## Çalıştırma
mvnw spring-boot:run

## Port
8082

## Endpoint'ler (Gateway üzerinden JWT token gerektirir)
POST /api/customers - Yeni müşteri oluştur
GET /api/customers/{id} - Tek müşteri sorgula (sahiplik kontrolü var)
GET /api/customers - Kendi müşteri kayıtlarını sayfalı şekilde listele
(ADMIN tümünü görür). Query parametreleri: `?page=0&size=20&sort=id,desc`
(hiçbiri verilmezse varsayılan: sayfa 0, boyut 20) - bkz. "Sayfalama"
PUT /api/customers/{id} - Müşteri güncelle (sahiplik kontrolü var)
DELETE /api/customers/{id} - Müşteri sil (sahiplik kontrolü var)
GET /api/customers/internal/{id} - Müşterinin var olup olmadığını kontrol eder
(yalnızca Account Service tarafından, Feign üzerinden çağrılır; Gateway
dışarıdan gelen isteklere kapatmıştır)

## Veritabanı
PostgreSQL - customer_db

## Yetkilendirme (Authorization)
Her müşteri kaydı, `userId` alanıyla bir kullanıcıya bağlıdır (Gateway'in
JWT'den çıkarıp `X-User-Id` header'ıyla ilettiği kimlik). Kurallar:
- **Okuma** (`getById`, `getAll`): ADMIN rolü herkesin kaydını
  görebilir, diğer kullanıcılar sadece kendi kaydını.
- **Yazma** (`update`, `delete`): ADMIN dahil, sadece kaydın gerçek
  sahibi işlem yapabilir - admin'e kısıtlı (sadece okuma) yetki
  verildi, bu bilinçli bir tasarım kararı.

Yetkisiz bir işlem denemesi `403 Forbidden` döner (`AccessDeniedException`).
Detaylı gerekçe için bkz. docs/asama8-notlar.md, "Gün 1 — Konu B".

Rol kontrolü (`Role.valueOf(role) == Role.ADMIN`), Gateway'in `X-User-Role`
header'ıyla ilettiği string'i tip güvenli bir `Role` enum'una (`CUSTOMER`,
`ADMIN`) çevirerek yapılır. Bilinçli bir tasarım kararı: `role` header'ı
Gateway'in kendi doğruladığı JWT'den türediği için normalde her zaman
geçerli bir değerdir; beklenmedik bir değer gelirse (bug/veri bütünlüğü
sorunu) sistem sessizce "yetkisiz" varsaymak yerine `400 Bad Request`
ile açıkça hata verir (bkz. docs/asama8-notlar.md, "Gün 4 — Madde 5.4").

## Sayfalama
`GET /api/customers`, DB'deki tüm kayıtları tek seferde dönmek yerine
Spring Data'nın `Pageable`/`Page` desteğiyle sayfalanmış sonuç döner.
CUSTOMER rolü için filtreleme artık DB seviyesinde (`findByUserId`)
yapılır, önceden olduğu gibi tüm kayıtları çekip Java tarafında
filtrelemez - bu hem performans hem veri transferi açısından daha
verimlidir (bkz. docs/asama8-notlar.md, "Gün 4 — Madde 6").

## Loglama ve correlationId
Gelen her istekteki `X-Correlation-Id` header'ı, `CorrelationIdFilter`
tarafından SLF4J MDC'ye (`correlationId`) konur; header yoksa ya da
şüpheliyse (harf, rakam ve tire dışında karakter, 8–64 karakter dışında
uzunluk) yeni bir UUID üretilir. Id, o isteğe ait tüm log satırlarına
otomatik eklenir, cevaba da yazılır ve istek bitince MDC temizlenir.
Account Service müşteri varlık kontrolü için bu servisi çağırdığında, kendi
isteğinin id'sini `X-Correlation-Id` header'ıyla gönderir; böylece hesap
açma akışı iki serviste aynı id ile izlenebilir.

`docker` profilinde (Compose'ta `SPRING_PROFILES_ACTIVE: docker`) loglar
JSON formatındadır ve `correlationId` ayrı bir alandır (bkz.
docs/asama8-notlar.md, "Yapılandırılmış Loglama ve correlationId").

## Notlar
Kimlik numarası (identityNumber) ve email alanları güncelleme
işleminde değiştirilemez; bu alanlar sadece oluşturma sırasında
belirlenir.

## Test
Bu servis için hâlâ CustomerService'e özel bir iş mantığı unit testi yok
(basit CRUD mantığı içerdiği için Aşama 6'da öncelikli olarak Auth, Account
ve Transaction Service'lere odaklanıldı, bkz. docs/asama6-notlar.md).
Mevcut testler: `CustomerServiceApplicationTests` (context-load) ve
`CorrelationIdFilterTest` (id üretimi, geçerli id'nin korunması, şüpheli
id'nin değiştirilmesi, MDC'nin istek sonunda temizlenmesi). Gün 4'teki enum
(Role) ve sayfalama değişiklikleri, derleme/başlatma seviyesinde ve Docker +
Postman ile uçtan uca doğrulandı (bkz. docs/asama8-notlar.md, "Gün 4 —
Madde 5.4, Madde 6").

## API Dokümantasyonu
http://localhost:8082/swagger-ui.html
Merkezi (tüm servisler): http://localhost:8080/swagger-ui.html