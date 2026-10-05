# Eureka Server

Servislerin birbirini bulabilmesi için kullanılan service discovery sunucusu.

## Çalıştırma
mvnw spring-boot:run

## Port
8761

## Dashboard
http://localhost:8761

## API Dokümantasyonu
Bu servisin kendi REST endpoint'i olmadığı için (sadece service
discovery işlevi görür), Swagger/OpenAPI eklenmedi - bilinçli bir
kapsam kararı (bkz. docs/asama6-notlar.md).

## Loglama
Bu servis, diğerlerinden farklı olarak JSON log ve correlationId
kullanmaz (kendi REST endpoint'i olmadığı için istek izlemesi gerekmez);
loglar Spring'in varsayılan konsol formatındadır (bkz.
docs/asama8-notlar.md, "Yapılandırılmış Loglama ve correlationId",
"Sınırlamalar").