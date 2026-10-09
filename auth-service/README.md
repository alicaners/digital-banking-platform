# Auth Service

Kullanıcı kimlik doğrulama servisi. Kayıt, giriş, JWT (access) ve refresh
token üretimi, token yenileme ve çıkış (logout) tamamlanmış durumda.

## Çalıştırma
mvnw spring-boot:run

## Port
8081

## Endpoint'ler
POST /api/auth/register - Kullanıcı kaydı (token gerekmez)
POST /api/auth/login - Giriş, access token (`token`) ve `refreshToken` döner (token gerekmez)
POST /api/auth/refresh - Body: `{"refreshToken": "..."}`. Yeni access token ve yeni refresh token döner (token gerekmez)
POST /api/auth/logout - Body: `{"refreshToken": "..."}`. Refresh token'ı iptal eder, 204 döner (token gerekmez)
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

`User.role` alanı artık düz bir `String` değil, `Role` enum'u
(`CUSTOMER`, `ADMIN`) - veritabanında yine okunabilir string olarak
saklanıyor, Java tarafında yazım hatasına kapalı bir tip. Yeni
kaydolan her kullanıcı otomatik olarak `CUSTOMER` rolüyle oluşturulur;
`ADMIN` rolü şu an self-servis bir yolla atanamıyor, DB'de elle
atanıyor. JWT claim'i (`role`) hâlâ `String` olarak kalıyor - token
formatı ve bunu okuyan Gateway/diğer servisler etkilenmedi, sadece
`AuthService` içinde enum'dan string'e (`user.getRole().name()`) çevrim
yapılıyor (bkz. docs/asama8-notlar.md, "Gün 4 — Madde 5.3").

## Refresh Token ve Logout
Access token (JWT) artık kısa ömürlüdür: 15 dakika (`JWT_EXPIRATION`,
varsayılan 900000 ms). Login ayrıca 7 gün geçerli bir refresh token
(`JWT_REFRESH_EXPIRATION`, varsayılan 604800000 ms) verir. Refresh token bir
JWT değil, rastgele üretilmiş bir değerdir ve veritabanında (`refresh_tokens`
tablosu) yalnızca SHA-256 hash'i saklanır.

- `/api/auth/refresh` token'ı **döndürür (rotation)**: eski refresh token iptal
  edilir, yenisi verilir; her token tek kullanımlıktır.
- İptal edilmiş bir token tekrar gelirse kullanıcının tüm aktif refresh
  token'ları iptal edilir (reuse tespiti) ve 401 döner.
- `/api/auth/logout` idempotenttir: bilinmeyen ya da zaten iptal edilmiş token
  için de 204 döner.
- Logout sonrası mevcut access token, süresi dolana kadar (en fazla 15 dakika)
  geçerli kalır, çünkü Gateway token'ı stateless doğrular.

Gateway'de `/api/auth/refresh` ve `/api/auth/logout` JWT istemeyen açık yollardır
(rate limiter yine uygulanır). Ayrıntılar ve sınırlamalar için bkz.
docs/asama8-notlar.md, "Refresh Token ve Logout".

## Güvenlik Notu

Aşama 8'de giderildi: `jwt.secret` için kod içinde bir varsayılan
değer artık **yok**. `application.yml`'de `${JWT_SECRET}` (varsayılansız)
kullanılıyor - gerçek bir `.env` dosyası (bkz. proje kökündeki
`.env.example`) olmadan uygulama hiç başlamıyor. Bu, önceki bir
incelemede (code review) tespit edilen kritik bir güvenlik açığıydı
(bkz. docs/asama8-notlar.md, "Gün 1 — Konu A").

## Loglama ve correlationId
Gelen her istekteki `X-Correlation-Id` header'ı, `CorrelationIdFilter`
tarafından SLF4J MDC'ye (`correlationId`) konur; header yoksa ya da
şüpheliyse (harf, rakam ve tire dışında karakter, 8–64 karakter dışında
uzunluk) yeni bir UUID üretilir. Id, o isteğe ait tüm log satırlarına
otomatik eklenir, cevaba da yazılır ve istek bitince MDC temizlenir.

`docker` profilinde (Compose'ta `SPRING_PROFILES_ACTIVE: docker`) loglar
JSON formatındadır ve `correlationId` ayrı bir alandır (bkz.
docs/asama8-notlar.md, "Yapılandırılmış Loglama ve correlationId").

## Test
Unit testler (Mockito) ile register ve login metodlarının tüm
senaryoları kapsanmıştır; token üretimi artık `userId`/`role`
parametreleriyle doğrulanır. Ayrıca `CorrelationIdFilterTest`, id
üretimini, geçerli id'nin korunmasını, şüpheli id'nin değiştirilmesini
ve MDC'nin istek sonunda temizlenmesini doğrular (bkz.
docs/asama6-notlar.md, docs/asama8-notlar.md).

`RefreshTokenServiceTest`, refresh token'ın yalnızca hash olarak saklanmasını,
rotation'ı, süresi dolmuş/bilinmeyen/iptal edilmiş token'ın reddedilmesini,
reuse'da toplu iptali ve logout'un davranışını doğrular. `AuthServiceTest`
login, refresh ve logout akışlarını kapsar.

## API Dokümantasyonu
http://localhost:8081/swagger-ui.html
Merkezi (tüm servisler): http://localhost:8080/swagger-ui.html