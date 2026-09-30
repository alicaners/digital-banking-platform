# API Gateway

Dış dünyadan gelen tüm istekleri karşılayan, Eureka üzerinden ilgili
servise yönlendiren ve JWT token doğrulaması yapan tek giriş noktası.

## Çalıştırma
mvnw spring-boot:run

## Port
8080

## Güvenlik
Gateway, gelen isteklerdeki Authorization header'ını kontrol eder.
/api/auth/register ve /api/auth/login hariç tüm istekler geçerli
bir JWT token gerektirir. Token eksik ya da geçersizse istek ilgili
servise hiç ulaştırılmadan 401 Unauthorized döner.

Token doğrulandıktan sonra, Gateway içindeki `userId` ve `role`
bilgilerini çıkarıp isteği `X-User-Id` ve `X-User-Role` header'larıyla
zenginleştirir, sonra ilgili servise yönlendirir. Bu header'lar
**sadece Gateway tarafından** eklenir - client'ın kendi isteğine
sahte bir `X-User-Id` eklemeye çalışması, Gateway'in üzerine yazdığı
gerçek değerle geçersiz kılınır. Downstream servisler (Customer,
Account, Transaction), bu header'ları kullanarak kendi ownership/
yetkilendirme kontrollerini yapar (bkz. docs/asama8-notlar.md,
"Gün 1 — Konu B").

## İç Servis Çağrılarının Korunması (`/internal/**`)
Servisler arası çağrılar için kullanılan endpoint'ler (örn.
`account-service`'teki `internal/transfer`) yalnızca container-to-
container iletişim için tasarlanmıştır ve normalde JWT taşımaz -
Feign, servisler arası çağrılarda token eklemez. Bu, `/internal/`
yolunun dışarıdan (Gateway üzerinden) doğrudan çağrılabildiği bir
açık oluşturuyordu: token'sız bir istek, JWT kontrolünden istisna
tutulan open endpoint gibi davranıp doğrudan downstream servise
ulaşabilirdi.

Bunu kapatmak için `JwtAuthenticationFilter`, JWT/open-endpoint
kontrollerinden **önce** çalışan bir kontrol ekler: path içinde
`/internal/` geçen her istek, kimden geldiğine bakılmadan doğrudan
403 Forbidden ile reddedilir. Bu sayede `/internal/**` altındaki
endpoint'ler sadece servisler arası (Gateway'in dışından erişilemeyen)
çağrılara açık kalır (bkz. docs/asama8-notlar.md, "Gün 3 — Madde 1").

## Rate Limiting
Tüm isteklere (JWT kontrolünden bile önce) global bir istek sınırı
uygulanır: 10 saniyede en fazla 10 istek kabul edilir, aşımda
429 Too Many Requests döner.

Bu sınırlama `application.yml`'deki `resilience4j.ratelimiter.instances.globalRateLimiter`
ayarlarından okunur - `RateLimiterFilter`, `RateLimiterRegistry` üzerinden
bu adla tanımlı gerçek bir `RateLimiter` nesnesi kullanır. (Önceden bu
yml ayarları tanımlıydı ama filtre koduyla hiç bağlanmamıştı, yani
sessizce hiç uygulanmıyordu - bkz. docs/asama8-notlar.md, "Gün 4 — Madde 3".)

## Route'lar
/api/auth/**          -> auth-service
/api/customers/**     -> customer-service
/api/accounts/**      -> account-service
/api/transactions/**  -> transaction-service

## Örnek Kullanım
POST http://localhost:8080/api/auth/register  (token gerekmez)
POST http://localhost:8080/api/auth/login     (token gerekmez, JWT döner)
GET  http://localhost:8080/api/customers      (Authorization: Bearer <token> gerekir)

## API Dokümantasyonu (Merkezi)
Tüm mikroservislerin API'leri, Gateway üzerinden tek bir sayfada
toplanmıştır: http://localhost:8080/swagger-ui.html
Sağ üstteki dropdown'dan istenen servis (Auth, Customer, Account,
Transaction, Notification) seçilerek o servisin endpoint'leri
incelenebilir. Bu dokümantasyon adresleri token gerektirmeden
erişilebilir (bkz. docs/asama6-notlar.md).