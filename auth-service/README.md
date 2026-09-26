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
token, artık sadece username değil, `userId` ve `role` bilgilerini de
custom claim olarak taşır. Gateway, token'ı doğruladıktan sonra bu
bilgileri `X-User-Id`/`X-User-Role` header'larıyla downstream
servislere iletir - bu, her servisin kendi ownership/yetkilendirme
kontrolünü yapabilmesini sağlar (bkz. docs/asama8-notlar.md,
"Gün 1 — Konu B").

## Güvenlik Notu

Aşama 8'de giderildi: `jwt.secret` için kod içinde bir varsayılan
değer artık **yok**. `application.yml`'de `${JWT_SECRET}` (varsayılansız)
kullanılıyor - gerçek bir `.env` dosyası (bkz. proje kökündeki
`.env.example`) olmadan uygulama hiç başlamıyor. Bu, önceki bir
incelemede (code review) tespit edilen kritik bir güvenlik açığıydı
(bkz. docs/asama8-notlar.md, "Gün 1 — Konu A").

## Test
Unit testler (Mockito) ile register ve login metodlarının tüm
senaryoları kapsanmıştır; token üretimi artık `userId`/`role`
parametreleriyle doğrulanır (bkz. docs/asama6-notlar.md,
docs/asama8-notlar.md).

## API Dokümantasyonu
http://localhost:8081/swagger-ui.html
Merkezi (tüm servisler): http://localhost:8080/swagger-ui.html