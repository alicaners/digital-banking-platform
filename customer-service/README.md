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

## Notlar
Kimlik numarası (identityNumber) ve email alanları güncelleme
işleminde değiştirilemez; bu alanlar sadece oluşturma sırasında
belirlenir.

## Test
Bu servis için hâlâ CustomerService'e özel bir unit test dosyası yok
(sadece `CustomerServiceApplicationTests` ile context-load testi
mevcut) - basit CRUD mantığı içerdiği için Aşama 6'da öncelikli olarak
Auth, Account ve Transaction Service'lere odaklanıldı
(bkz. docs/asama6-notlar.md). Gün 4'teki enum (Role) ve sayfalama
değişiklikleri, mevcut context-load testiyle derleme/başlatma
seviyesinde doğrulandı, Docker + Postman ile uçtan uca test edildi
(bkz. docs/asama8-notlar.md, "Gün 4 — Madde 5.4, Madde 6").

## API Dokümantasyonu
http://localhost:8082/swagger-ui.html
Merkezi (tüm servisler): http://localhost:8080/swagger-ui.html