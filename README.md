# Digital Banking Platform

![CI Status](https://github.com/alicaners/digital-banking-platform/actions/workflows/ci.yml/badge.svg)

Spring Boot ve Spring Cloud ile geliştirilmiş mikroservis mimarili
dijital bankacılık platformu simülasyonu.

## Mimari

Client → API Gateway (JWT doğrulama, Rate Limiting) → Eureka (Service Discovery) → İlgili Mikroservis

```mermaid
graph TD
    Client[Client] --> Gateway[API Gateway<br/>JWT + Rate Limiting]
    Gateway --> Eureka[Eureka Server<br/>Service Discovery]
    Gateway --> Auth[Auth Service<br/>:8081]
    Gateway --> Customer[Customer Service<br/>:8082]
    Gateway --> Account[Account Service<br/>:8083<br/>Redis Cache]
    Gateway --> Transaction[Transaction Service<br/>:8084<br/>Saga + Circuit Breaker]

    Auth --> AuthDB[(auth_db)]
    Customer --> CustomerDB[(customer_db)]
    Account --> AccountDB[(account_db)]
    Transaction --> TransactionDB[(transaction_db)]

    Transaction -->|Feign Client| Account
    Transaction -->|Kafka Event| Notification[Notification Service<br/>:8085]

    Account -.->|Cache| Redis[(Redis)]

    style Gateway fill:#4A90D9,color:#fff
    style Eureka fill:#7B68EE,color:#fff
    style Notification fill:#50C878,color:#fff
```

## Servisler

| Servis | Durum | Port | Açıklama |
|---|---|---|---|
| eureka-server | ✅ Tamamlandı | 8761 | Service Discovery |
| api-gateway | ✅ Tamamlandı | 8080 | Tek giriş noktası + JWT doğrulama + Rate Limiting + Merkezi Swagger |
| auth-service | ✅ Tamamlandı | 8081 | Kimlik doğrulama, JWT üretimi |
| customer-service | ✅ Tamamlandı | 8082 | Müşteri yönetimi (CRUD) |
| account-service | ✅ Tamamlandı | 8083 | Hesap yönetimi, bakiye işlemleri, Redis cache |
| transaction-service | ✅ Tamamlandı | 8084 | Para transferi, Saga Pattern, Circuit Breaker, Retry |
| notification-service | ✅ Tamamlandı | 8085 | Kafka ile asenkron bildirim |

## Çalıştırma

**Önce bir kere:** Proje kök dizininde `.env.example` dosyasını
`.env` olarak kopyala ve içindeki `JWT_SECRET` değerini kendi
belirleyeceğin, en az 32 karakterlik güçlü bir değerle doldur:

```bash
copy .env.example .env
```

(Bu adım olmadan auth-service ve api-gateway, JWT_SECRET bulamadığı
için başlamaz.)

**En kolay yol — Docker Compose ile tüm sistemi tek komutla ayağa kaldırmak:**

```bash
docker compose up -d --build
```

Bu komut, altyapıyı (PostgreSQL, Kafka, Zookeeper, Redis) ve yedi
mikroservisi hep birlikte, doğru sırayla başlatır — IntelliJ veya
Maven kurmaya gerek kalmadan. `--build` bayrağı, ilk çalıştırmada
image'ların Dockerfile'lardan sıfırdan inşa edilmesini sağlar; sonraki
çalıştırmalarda `--build` olmadan da (`docker compose up -d`) kullanılabilir.

**Güvenlik notu:** Sadece api-gateway'in portu (8080) dışarıya açıktır.
Diğer altı mikroservis yalnızca Docker network'ü içinden erişilebilir —
tüm istekler zorunlu olarak Gateway üzerinden (ve dolayısıyla JWT
kontrolünden) geçer.

**Alternatif — Her servisi elle, IntelliJ/terminal üzerinden çalıştırmak:**

1. Docker altyapısını başlat: `docker compose up -d` (sadece postgres, kafka, redis, zookeeper için de kullanılabilir)
2. Eureka Server'ı başlat: `cd eureka-server && mvnw spring-boot:run`
3. API Gateway'i başlat: `cd api-gateway && mvnw spring-boot:run`
4. Auth Service'i başlat: `cd auth-service && mvnw spring-boot:run`
5. Customer Service'i başlat: `cd customer-service && mvnw spring-boot:run`
6. Account Service'i başlat: `cd account-service && mvnw spring-boot:run`
7. Transaction Service'i başlat: `cd transaction-service && mvnw spring-boot:run`
8. Notification Service'i başlat: `cd notification-service && mvnw spring-boot:run`

## Güvenlik

Tüm istekler API Gateway üzerinden geçer. `/api/auth/register` ve
`/api/auth/login` hariç her endpoint, geçerli bir JWT token
gerektirir. Şifreler BCrypt ile hash'lenerek saklanır. Gateway
ayrıca tüm isteklere 10 saniyede 10 istek sınırı (Rate Limiting)
uygular.

## Servisler Arası İletişim

Transaction Service, Account Service'e Feign Client üzerinden
senkron HTTP çağrıları yapar (servis keşfi Eureka üzerinden).
Transaction Service, her işlem sonrası Kafka üzerinden Notification
Service'e asenkron olay (event) yayınlar.

## Distributed Transaction Yönetimi (Saga Pattern)

Transfer sırasında gönderen hesaptan para düşürüldükten sonra alıcı
hesaba eklenirken bir hata oluşursa, sistem otomatik olarak parayı
gönderen hesaba geri iade eder (compensating transaction). Bu,
Aşama 3'te tespit edilip Aşama 4'te çözülmüştür
(bkz. docs/asama3-notlar.md ve docs/asama4-notlar.md).

## Dayanıklılık ve Performans

- **Circuit Breaker**: Account Service çökerse, Transaction Service
  gereksiz beklemeden hızlı bir fallback cevabı döner.
- **Retry**: Geçici bağlantı hatalarında otomatik olarak en fazla
  3 kez tekrar deneme yapılır.
- **Rate Limiter**: Gateway seviyesinde aşırı istek yüküne karşı
  koruma sağlanır.
- **Redis Cache**: Sık sorgulanan hesap verileri cache'lenir, bakiye
  değiştiğinde cache otomatik tazelenir.

(bkz. docs/asama5-notlar.md)

## Test ve Dokümantasyon

- **Unit testler (Mockito)**: Auth, Account ve Transaction Service'te
  toplam 15 test, iş mantığının kritik senaryolarını kapsıyor.
- **Integration test (Testcontainers)**: Account Service için gerçek
  bir PostgreSQL container'ında çalışan testler.
- **Swagger/OpenAPI**: Beş servisin API'si, Gateway üzerinden tek bir
  merkezi sayfada toplandı: http://localhost:8080/swagger-ui.html

(bkz. docs/asama6-notlar.md)

## DevOps

- **Docker**: Her servis, multi-stage build ile optimize edilmiş
  (165-206MB) bağımsız bir image olarak build edilebiliyor.
- **Docker Compose**: Tüm sistem (11 container — 4 altyapı + 7
  uygulama), tek bir `docker compose up -d --build` komutuyla, ortak
  bir Docker network'ü üzerinden birbirine bağlı şekilde ayağa kalkıyor.
- **GitHub Actions (CI)**: Her push'ta, yedi servisin her biri ayrı
  ayrı otomatik olarak derlenip test ediliyor (bkz. yukarıdaki badge).

(bkz. docs/asama7-notlar.md)

## Teknolojiler

Java 21, Spring Boot 3.3.4, Spring Cloud 2023.0.3, PostgreSQL 16,
Kafka, Redis, Docker, Docker Compose, GitHub Actions, JWT (jjwt),
OpenFeign, Resilience4j, Spring Retry, JUnit 5, Mockito, Testcontainers,
Springdoc OpenAPI

## Durum

✅ Proje tamamlandı — 7 mikroservis, Eureka service discovery, Gateway
üzerinden merkezi JWT doğrulama ve rate limiting, senkron (Feign) ve
asenkron (Kafka) servisler arası iletişim, Saga Pattern ile distributed
transaction yönetimi, Circuit Breaker/Retry ile hata toleransı, Redis
ile performans optimizasyonu, otomatik testler (Unit + Integration),
merkezi API dokümantasyonu (Swagger), Docker ile tam konteynerleştirme
ve GitHub Actions ile sürekli entegrasyon (CI).

Devam eden çalışma: bir güvenlik/mimari incelemesi sonrası tespit
edilen bulgular doğrultusunda sağlamlaştırma (hardening) çalışmaları
sürdürülüyor (bkz. docs/asama8-notlar.md).