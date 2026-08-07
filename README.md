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
| account-service | ✅ Tamamlandı | 8083 | Hesap yönetimi, bakiye işlemleri |
| transaction-service | ✅ Tamamlandı (Saga öncesi) | 8084 | Para transferi |
| notification-service | ⏳ Planlandı | 8085 | Bildirimler |

## Çalıştırma Sırası

1. Docker altyapısını başlat: `docker compose up -d`
2. Eureka Server'ı başlat: `cd eureka-server && mvnw spring-boot:run`
3. API Gateway'i başlat: `cd api-gateway && mvnw spring-boot:run`
4. Auth Service'i başlat: `cd auth-service && mvnw spring-boot:run`
5. Customer Service'i başlat: `cd customer-service && mvnw spring-boot:run`
6. Account Service'i başlat: `cd account-service && mvnw spring-boot:run`
7. Transaction Service'i başlat: `cd transaction-service && mvnw spring-boot:run`

## Güvenlik

Tüm istekler API Gateway üzerinden geçer. `/api/auth/register` ve
`/api/auth/login` hariç her endpoint, geçerli bir JWT token
gerektirir. Şifreler BCrypt ile hash'lenerek saklanır.

## Servisler Arası İletişim

Transaction Service, Account Service'e Feign Client üzerinden
senkron HTTP çağrıları yapar (servis keşfi Eureka üzerinden).

## Bilinen Sınırlama

Transaction Service'teki transfer akışı şu anda distributed
transaction problemi içerir: gönderen hesaptan para düşürüldükten
sonra alıcı hesaba eklenirken bir hata oluşursa, para geri iade
edilmez. Bu, Aşama 4'te Saga Pattern ile çözülecektir
(bkz. docs/asama3-notlar.md).

## Teknolojiler

Java 21, Spring Boot 3.3.4, Spring Cloud 2023.0.3, PostgreSQL 16, Kafka, Docker, JWT (jjwt), OpenFeign

## Durum

🚧 Geliştirme aşamasında — Aşama 3 tamamlandı (Account Service: hesap
yönetimi/bakiye işlemleri, Transaction Service: Feign Client ile
servisler arası senkron iletişim, transfer akışı Saga öncesi haliyle)