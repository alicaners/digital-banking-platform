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

## Route'lar
/api/auth/**      -> auth-service
/api/customers/**  -> customer-service

## Örnek Kullanım
POST http://localhost:8080/api/auth/register  (token gerekmez)
POST http://localhost:8080/api/auth/login     (token gerekmez, JWT döner)
GET  http://localhost:8080/api/customers      (Authorization: Bearer <token> gerekir)