# Auth Service

Kullanıcı kimlik doğrulama servisi. Kayıt, giriş ve JWT token üretimi
tamamlanmış durumda.

## Çalıştırma
mvnw spring-boot:run

## Port
8081

## Endpoint'ler
POST /api/auth/register - Kullanıcı kaydı (token gerekmez)
POST /api/auth/login - Giriş, JWT token döner (token gerekmez)
GET /api/auth/ping - Servisin ve veritabanı bağlantısının sağlık kontrolü

## Veritabanı
PostgreSQL - auth_db

## Güvenlik
Şifreler BCrypt ile hash'lenerek saklanır. Giriş sonrası dönen JWT
token, Gateway seviyesinde doğrulanarak korumalı endpoint'lere erişim
kontrolü sağlanır.

## Güvenlik Notu

Bu proje eğitim amaçlıdır, `application.yml` içinde `jwt.secret` gibi
değerler için varsayılanlar bulunur. Gerçek bir prodüksiyon ortamında
bu tür hassas değerler asla kod içine yazılmamalı; ortam değişkeni
(bkz. proje kökündeki `.env.example`) ya da bir secret management
servisi (HashiCorp Vault, AWS Secrets Manager vb.) üzerinden
yönetilmelidir.