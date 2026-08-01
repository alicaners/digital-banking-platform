# Digital Banking Platform

Spring Boot ve Spring Cloud ile geliştirilmiş mikroservis mimarili
dijital bankacılık platformu simülasyonu.

## Mimari

Client → API Gateway → Eureka (Service Discovery) → İlgili Mikroservis

## Servisler

| Servis | Durum | Port | Açıklama |
|---|---|---|---|
| eureka-server | ✅ Çalışıyor | 8761 | Service Discovery |
| api-gateway | ✅ Çalışıyor | 8080 | Tek giriş noktası |
| auth-service | 🚧 İskelet hazır | 8081 | Kimlik doğrulama |
| customer-service | ⏳ Planlandı | 8082 | Müşteri yönetimi |
| account-service | ⏳ Planlandı | 8083 | Hesap yönetimi |
| transaction-service | ⏳ Planlandı | 8084 | Para transferi |
| notification-service | ⏳ Planlandı | 8085 | Bildirimler |

## Çalıştırma Sırası

1. Docker altyapısını başlat: `docker compose up -d`
2. Eureka Server'ı başlat: `cd eureka-server && mvnw spring-boot:run`
3. API Gateway'i başlat: `cd api-gateway && mvnw spring-boot:run`
4. Auth Service'i başlat: `cd auth-service && mvnw spring-boot:run`

## Teknolojiler

Java 21, Spring Boot 3.3.4, Spring Cloud 2023.0.3, PostgreSQL 16, Kafka, Docker

## Durum

🚧 Geliştirme aşamasında — Hafta 1 tamamlandı (temel altyapı + Auth Service iskeleti)