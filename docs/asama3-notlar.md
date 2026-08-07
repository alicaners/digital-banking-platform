# Aşama 3 — Öğrenilenler ve Karşılaşılan Sorunlar

## Kritik Gözlem: Distributed Transaction Problemi (Bilinçli Olarak Bırakıldı)

transaction-service.transfer() metodu, önce gönderen hesaptan parayı
düşürüyor, sonra alıcı hesaba ekliyor. Bu iki işlem arasında bir hata
olursa (örn. geçersiz receiverAccountId), gönderenin parası düşmüş
oluyor ama alıcıya hiç ulaşmıyor — para "kayboluyor".

**Test ile doğrulandı**: Hesap 1'in bakiyesi 600 TL'ydi, receiverAccountId
9999 (var olmayan bir hesap) olan 50 TL'lik bir transfer denendi.
İşlem "FAILED" olarak işaretlendi ama hesap 1'in bakiyesi yine de
550 TL'ye düştü — 50 TL sistemde hiçbir yere ulaşmadan kayboldu.

Bu, mikroservis mimarisinde "distributed transaction" problemi olarak
bilinir — her servisin kendi ayrı veritabanı olduğu için, klasik
veritabanı transaction'ları (ACID) burada işe yaramıyor.

**Çözüm planı**: Aşama 4'te Saga Pattern (compensating transaction
mantığıyla) bu sorunu çözeceğiz — deposit başarısız olursa, withdraw
işlemini geri alan (paranın gönderen hesaba iade edildiği) bir
mekanizma kuracağız.

## Diğer Karşılaşılan Sorunlar

### pom.xml Hataları Dördüncü Kez Tekrarlandı
transaction-service oluşturulurken yine aynı hatalar çıktı (yanlış
Boot versiyonu 4.1.0 yerine 3.3.4, spring-boot-starter-webmvc yerine
spring-boot-starter-web, ayrı "-test" paketleri yerine tek
spring-boot-starter-test). Bu, Spring Initializr'da her seferinde
Boot versiyonunu elle seçmenin önemini bir kez daha gösterdi.

### Validation Hataları GlobalExceptionHandler Tarafından Yakalanmıyor
@Positive, @NotBlank gibi annotation'ların fırlattığı
MethodArgumentNotValidException, mevcut GlobalExceptionHandler'da
(sadece IllegalArgumentException yakalıyor) işlenmiyor, Spring'in
varsayılan hata formatına düşülüyor. Tüm servislerde (auth, customer,
account, transaction) geçerli. İleride merkezi bir handler ile
düzeltilebilir.

### IntelliJ Maven Senkronizasyon Sorunu Tekrarlandı
Yeni bağımlılıklar (jjwt, openfeign) pom.xml'e eklendikten sonra
IntelliJ'nin bunları tanıması için tek reload çoğu zaman yetmiyor,
bazen Invalidate Caches gerekiyor. Terminalden mvnw ile doğrulama
yapmak, sorunun gerçek mi (pom.xml hatası) yoksa sadece IDE
senkronizasyonu mu olduğunu ayırt etmede güvenilir bir yöntem oldu.

## Bu Aşamada Öğrenilenler
- BigDecimal ile doğru para hesaplaması (compareTo kullanımı, == veya
  <, > operatörleriyle asla karşılaştırılmaması gerektiği)
- Optimistic locking (@Version) kavramı — kod seviyesinde uygulandı,
  gerçek eşzamanlı çakışma senaryosu Postman ile manuel test edilemedi,
  ileride bir yük testi (JMeter, çoklu thread'li script) ile
  doğrulanabilir
- Feign Client ile servisler arası senkron iletişim
- @EnableFeignClients'ın gerekliliği
- Distributed transaction probleminin gerçek, gözle görülür kanıtı —
  ders kitabı tanımı değil, kendi test rakamlarıyla doğrulandı

## Bilinen Eksikler (İleride Geliştirilebilir)
- Account Service'te tüm hesapları listeleyen bir endpoint yok
- Validation hata mesajları tüm servislerde tutarlı formatta değil

## 4. Aşamaya Not
- Saga Pattern uygulanacak (compensating transaction)
- Belki notification-service ve Kafka'ya giriş