# Transaction Service

İki hesap arasında para transferi işlemlerini yöneten servis.

## Çalıştırma
mvnw spring-boot:run

## Port
8084

## Endpoint'ler (Gateway üzerinden JWT token gerektirir)
POST /api/transactions/transfer - İki hesap arasında transfer yapar

## Veritabanı
PostgreSQL - transaction_db

## Servisler Arası İletişim
Account Service'e Feign Client (AccountServiceClient) üzerinden
senkron HTTP çağrıları yapılır.

## Bilinen Sınırlama
Şu anki transfer akışı, distributed transaction problemi içerir:
gönderen hesaptan para düşürüldükten sonra alıcı hesaba eklenirken
bir hata oluşursa, para geri iade edilmez. Bu, ilerleyen bir aşamada
Saga Pattern ile çözülecektir (bkz. docs/asama3-notlar.md).