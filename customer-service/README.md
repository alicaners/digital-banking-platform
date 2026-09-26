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
GET /api/customers - Kendi müşteri kaydını listele (ADMIN tümünü görür)
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

## Notlar
Kimlik numarası (identityNumber) ve email alanları güncelleme
işleminde değiştirilemez; bu alanlar sadece oluşturma sırasında
belirlenir.

## Test
Bu servis için otomatik test kapsamı henüz eklenmedi (basit CRUD
mantığı içerdiği için Aşama 6'da öncelikli olarak Auth, Account ve
Transaction Service'lere odaklanıldı, bkz. docs/asama6-notlar.md).

## API Dokümantasyonu
http://localhost:8082/swagger-ui.html
Merkezi (tüm servisler): http://localhost:8080/swagger-ui.html