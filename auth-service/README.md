# Auth Service

Kullanıcı kimlik doğrulama servisi. Şu an sadece iskelet aşamasında,
login/register ve JWT üretimi bir sonraki haftada eklenecek.

## Çalıştırma
mvnw spring-boot:run

## Port
8081

## Endpoint'ler
GET /api/auth/ping - Servisin ve veritabanı bağlantısının sağlık kontrolü

## Veritabanı
PostgreSQL - auth_db
