# Customer Service

Müşteri kayıtlarının yönetildiği servis. Oluşturma, sorgulama,
güncelleme ve silme (CRUD) işlemlerini sağlar.

## Çalıştırma
mvnw spring-boot:run

## Port
8082

## Endpoint'ler (Gateway üzerinden JWT token gerektirir)
POST /api/customers - Yeni müşteri oluştur
GET /api/customers/{id} - Tek müşteri sorgula
GET /api/customers - Tüm müşterileri listele
PUT /api/customers/{id} - Müşteri güncelle
DELETE /api/customers/{id} - Müşteri sil

## Veritabanı
PostgreSQL - customer_db

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