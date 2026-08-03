# Hafta 2 — Öğrenilenler ve Karşılaşılan Sorunlar

## Karşılaşılan Sorunlar

### 1. customer-service ve api-gateway'de Tekrar Eden pom.xml Hataları
Yeni proje oluştururken (customer-service) Hafta 1'deki aynı hatalar
tekrarlandı: yanlış Spring Boot versiyonu, var olmayan starter isimleri
(spring-boot-starter-webmvc, spring-boot-starter-data-jpa-test vb.).
Çözüm: Boot 3.3.4 + Spring Cloud 2023.0.3 + doğru starter isimlerini
her yeni serviste bilinçli olarak kontrol etme alışkanlığı.

### 2. api-gateway'e jjwt Eklendikten Sonra IntelliJ Senkronizasyon Sorunu
pom.xml'e jjwt bağımlılıkları eklendikten sonra IntelliJ bunları
tanımadı (Cannot resolve symbol Jwts, Keys vb.), basit Maven reload
ve Invalidate Caches bile ilk seferde yeterli gelmedi.
Çözüm: pom.xml'in doğruluğu terminalden (mvnw dependency:tree ile)
doğrulandı, sonrasında IDE senkronizasyonu düzeldi.

## Bu Hafta Öğrenilenler
- BCrypt ile tek yönlü şifre hash'leme
- JWT üretimi ve doğrulaması (jjwt kütüphanesi)
- Spring Security'nin PasswordEncoder mekanizması
- Merkezi hata yönetimi (@RestControllerAdvice)
- DTO Request/Response ayrımı ile API sözleşmesini entity'den bağımsızlaştırma
- Spring Cloud Gateway'de GlobalFilter ile merkezi güvenlik katmanı
- Reaktif programlamaya giriş (Mono, WebFlux)
- Route yapısını sadeleştirme: discovery locator yerine net,
  elle tanımlı route'lar

## 3. Haftaya Not
- Account Service ve Transaction Service eklenecek
- Transaction Service'te Saga Pattern ele alınacak