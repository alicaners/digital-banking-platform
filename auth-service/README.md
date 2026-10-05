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

`User.role` alanı artık düz bir `String` değil, `Role` enum'u
(`CUSTOMER`, `ADMIN`) - veritabanında yine okunabilir string olarak
saklanıyor, Java tarafında yazım hatasına kapalı bir tip. Yeni
kaydolan her kullanıcı otomatik olarak `CUSTOMER` rolüyle oluşturulur;
`ADMIN` rolü şu an self-servis bir yolla atanamıyor, DB'de elle
atanıyor. JWT claim'i (`role`) hâlâ `String` olarak kalıyor - token
formatı ve bunu okuyan Gateway/diğer servisler etkilenmedi, sadece
`AuthService` içinde enum'dan string'e (`user.getRole().name()`) çevrim
yapılıyor (bkz. docs/asama8-notlar.md, "Gün 4 — Madde 5.3").

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

## API Dokümantasyonu
http://localhost:8081/swagger-ui.html
Merkezi (tüm servisler): http://localhost:8080/swagger-ui.html