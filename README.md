# Digital Banking Platform

Spring Boot ve Spring Cloud ile geliştirilmiş mikroservis mimarili
dijital bankacılık platformu simülasyonu.

## Mimari

Client → API Gateway (JWT doğrulama) → Eureka (Service Discovery) → İlgili Mikroservis

## Servisler

| Servis | Durum | Port | Açıklama |
|---|---|---|---|
| eureka-server | ✅ Tamamlandı | 8761 | Service Discovery |
| api-gateway | ✅ Tamamlandı | 8080 | Tek giriş noktası + JWT doğrulama |
| auth-service | ✅ Tamamlandı | 8081 | Kimlik doğrulama, JWT üretimi |
| customer-service | ✅ Tamamlandı | 8082 | Müşteri yönetimi (CRUD) |
| account-service | ⏳ Planlandı | 8083 | Hesap yönetimi |
| transaction-service | ⏳ Planlandı | 8084 | Para transferi |
| notification-service | ⏳ Planlandı | 8085 | Bildirimler |

## Çalıştırma Sırası

1. Docker altyapısını başlat: `docker compose up -d`
2. Eureka Server'ı başlat: `cd eureka-server && mvnw spring-boot:run`
3. API Gateway'i başlat: `cd api-gateway && mvnw spring-boot:run`
4. Auth Service'i başlat: `cd auth-service && mvnw spring-boot:run`
5. Customer Service'i başlat: `cd customer-service && mvnw spring-boot:run`

## Güvenlik

Tüm istekler API Gateway üzerinden geçer. `/api/auth/register` ve
`/api/auth/login` hariç her endpoint, geçerli bir JWT token
gerektirir. Şifreler BCrypt ile hash'lenerek saklanır.

## Teknolojiler

Java 21, Spring Boot 3.3.4, Spring Cloud 2023.0.3, PostgreSQL 16, Kafka, Docker, JWT (jjwt)

## Durum

🚧 Geliştirme aşamasında — Hafta 2 tamamlandı (Auth Service: kayıt/login/JWT,
Customer Service: CRUD, Gateway: merkezi JWT doğrulama)