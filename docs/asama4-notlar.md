# Aşama 4 — Öğrenilenler ve Karşılaşılan Sorunlar

## Saga Pattern Uygulaması (Compensating Transaction)

Aşama 3'te tespit edilen distributed transaction problemi çözüldü.
transfer() metoduna, deposit aşamasında hata oluşursa withdraw
işlemini geri alan (compensate) bir mekanizma eklendi.

**Test ile doğrulandı**: Hesap 1'in bakiyesi 480 TL'ydi, geçersiz
bir hesaba (9999) 50 TL transfer denendi. İşlem "REVERSED" olarak
işaretlendi ve hesap 1'in bakiyesi tekrar 480 TL'ye döndü — Aşama 3'te
para kalıcı olarak kaybolurken, artık güvenli şekilde geri alınıyor.

**Yeni transaction durumları:**
- COMPLETED: her iki adım da başarılı
- FAILED: withdraw'ın kendisi başarısız oldu (para hiç hareket etmedi)
- REVERSED: withdraw başarılı oldu ama deposit başarısız oldu,
  telafi (geri yatırma) başarıyla yapıldı

**Bilinen sınırlama**: Eğer telafi işleminin kendisi de başarısız
olursa (örn. Account Service o an erişilemezse), durum yine FAILED
olarak işaretleniyor ama bu sefer para gerçekten tutarsız bir
durumda kalabilir. Gerçek prodüksiyon sistemlerinde bu, bir "dead
letter queue" veya manuel müdahale süreciyle çözülür — bu projenin
kapsamı dışında bırakıldı.

## Hata Yönetimi İyileştirmeleri

FeignException özel olarak yakalanarak, Account Service'in döndürdüğü
gerçek hata mesajı (response body'sindeki "error" alanı) Jackson
ObjectMapper ile parse edilip failureReason alanına aktarılıyor.
JSON parse edilemezse (örn. servis tamamen erişilemezse), status
koduna dayalı genel bir mesaja güvenli şekilde düşülüyor.

**Test ile doğrulandı:**
- Geçersiz hesaba transfer → "REVERSED", failureReason: "Hesap bulunamadı"
- Yetersiz bakiye → "FAILED", failureReason: "Yetersiz bakiye"

Dört serviste de (auth, customer, account, transaction) validation
hataları (MethodArgumentNotValidException) artık GlobalExceptionHandler
tarafından yakalanıp anlamlı alan bazlı mesajlarla dönüyor — Aşama 3'te
not edilen sorun kapatıldı.

## Notification Service ve Kafka'ya Giriş

İlk kez asenkron servisler arası iletişim kuruldu. Transaction Service,
her transfer sonrası (durumdan bağımsız) transaction-events topic'ine
bir TransactionEvent mesajı yayınlıyor (KafkaTemplate ile). Notification
Service, @KafkaListener ile bu topic'i dinleyip anlamlı bir bildirim
mesajını log'a yazıyor (gerçek email/SMS gönderimi simüle edilmiyor,
bilinçli olarak basitleştirildi).

**Karşılaşılan sorun**: JsonSerializer, mesaj header'ına gönderen
tarafın paket yolunu (com.banking.transaction.event.TransactionEvent)
ekliyor. Notification Service'in kendi TransactionEvent kopyası farklı
pakette (com.banking.notification.event) olduğu için ClassNotFoundException
alındı. Çözüm: consumer tarafında spring.json.use.type.headers=false ve
spring.json.value.default.type ile hedef sınıf açıkça belirtildi.

**Test ile doğrulandı (bağımsızlık testi)**: Notification Service
kapatılıp bir transfer yapıldı (işlem #16) — Transaction Service hiç
etkilenmeden işlemi COMPLETED olarak tamamladı. Notification Service
tekrar başlatıldığında, committed offset'ten devam ederek kaçırdığı
mesajı otomatik yakaladı ve işledi. Bu, Kafka'nın servisleri
birbirinden bağımsızlaştırma avantajını somut olarak kanıtladı.

**Bilinen basitleştirme**: spring.json.trusted.packages "*" olarak
ayarlandı (tüm paketlere güven) — gerçek bir prodüksiyon sisteminde
sadece belirli, güvenilir paketlere izin verilirdi.

## Proje Genel Özeti (4 Aşama Sonunda)

- 7 mikroservis: Eureka, Gateway, Auth, Customer, Account, Transaction, Notification
- Merkezi kimlik doğrulama (JWT, Gateway seviyesinde)
- Senkron iletişim: Feign Client (Transaction → Account)
- Asenkron iletişim: Kafka (Transaction → Notification)
- Distributed transaction yönetimi: Saga Pattern (compensating transaction)
- Her servisin kendi veritabanı (database-per-service)
- BigDecimal ile doğru para hesaplaması, optimistic locking
- Docker Compose ile tüm altyapı (PostgreSQL, Kafka, Zookeeper)

## Sonraki Adımlar (Planlanan, Henüz Yapılmadı)

- Faz 4: Resilience4j ile Circuit Breaker, Retry, Rate Limiter; Redis cache
- Faz 5: Unit/Integration test (Mockito, Testcontainers), Swagger/OpenAPI
- Faz 6: Her servis için Dockerfile, uygulama servislerini de kapsayan
  tam docker-compose.yml, GitHub Actions ile CI pipeline