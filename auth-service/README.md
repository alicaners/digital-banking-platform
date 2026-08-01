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

## Güvenlik Notu

Bu proje eğitim amaçlıdır, `application.yml` içinde `jwt.secret` gibi
değerler için varsayılanlar bulunur. Gerçek bir prodüksiyon ortamında
bu tür hassas değerler asla kod içine yazılmamalı; ortam değişkeni
(bkz. proje kökündeki `.env.example`) ya da bir secret management
servisi (HashiCorp Vault, AWS Secrets Manager vb.) üzerinden
yönetilmelidir.