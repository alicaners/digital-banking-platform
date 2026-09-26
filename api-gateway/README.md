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

## Rate Limiting
Tüm isteklere (JWT kontrolünden bile önce) global bir istek sınırı
uygulanır: 10 saniyede en fazla 10 istek kabul edilir, aşımda
429 Too Many Requests döner.

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