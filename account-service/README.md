# Account Service

Müşteri hesaplarının açıldığı ve bakiye işlemlerinin yönetildiği servis.

## Çalıştırma
mvnw spring-boot:run

## Port
8083

## Endpoint'ler (Gateway üzerinden JWT token gerektirir)
POST /api/accounts - Yeni hesap aç
GET /api/accounts/{id} - Hesap bilgisi ve bakiye sorgula
GET /api/accounts - Tüm hesapları listele
POST /api/accounts/{id}/deposit - Hesaba para yatır
POST /api/accounts/{id}/withdraw - Hesaptan para çek

## Veritabanı
PostgreSQL - account_db

## Teknik Notlar

**Para miktarları**: Tüm bakiye alanları `BigDecimal` ile tutulur,
`double`/`float` kullanılmaz — ondalık yuvarlama hatalarının önüne
geçmek için.

**Eşzamanlılık**: `Account` entity'sinde `@Version` alanı ile
optimistic locking uygulanır. Kod seviyesinde doğrulandı; gerçek
eşzamanlı çakışma senaryosu Postman ile manuel test edilemedi
(bkz. docs/asama3-notlar.md).

**IBAN üretimi (basitleştirilmiş)**: Bu projede IBAN'lar, gerçek
ISO 7064 (MOD 97-10) checksum algoritması ve resmi banka kodları
kullanılmadan, formatça gerçekçi görünen rastgele sayılarla üretilir.
Gerçek bir bankacılık sisteminde bu, merkez bankası tarafından
sağlanan resmi kod listeleri ve checksum doğrulaması gerektirir —
bu proje kapsamında bilinçli olarak basitleştirilmiştir.

## Bilinen Eksik
Tüm hesapları listeleyen bir endpoint henüz yok, sadece ID ile
tekil sorgulama mevcut.