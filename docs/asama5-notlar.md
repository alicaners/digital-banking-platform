# Aşama 5 — Öğrenilenler ve Karşılaşılan Sorunlar

## Circuit Breaker Uygulaması (Resilience4j)

Transaction Service'in Account Service'e yaptığı Feign çağrısına
Circuit Breaker eklendi. Son 10 istekten %50'si başarısız olursa
devre açılıyor (OPEN), 10 saniye boyunca Account Service'e hiç
istek gönderilmeden fallback devreye giriyor, sonra otomatik olarak
tekrar denemeye geçiliyor (HALF_OPEN).

**Karşılaşılan sorun (önemli, çözümü iki adım gerektirdi)**:
1. `feign.circuitbreaker.enabled: true` ayarı Spring Cloud 2023.0.3'te
   tek başına yeterli olmadı, gerçek çalışan ayar
   `spring.cloud.openfeign.circuitbreaker.enabled: true` oldu.
2. Fallback sınıfı (AccountServiceClientFallback) @Component olmadan
   "No fallback instance found" hatasıyla uygulama hiç açılmadı —
   Feign'in Circuit Breaker fallback mekanizması, fallback sınıfının
   Spring bean container'ında kayıtlı olmasını zorunlu kılıyor.
   İkisi doğru ayarlanmadan sistem sessizce eski (fallback'siz)
   davranışa düşüyordu, hiçbir hata vermeden - bu da teşhisi
   zorlaştırdı.

**Test ile doğrulandı (tam yaşam döngüsü)**: Account Service kasıtlı
olarak kapatılıp art arda transfer denendi. İlk istekler yavaş ve
gerçek FeignException ile döndü, belirli sayıda başarısızlıktan
sonra istekler hızlanıp "Hesap servisi şu anda kullanılamıyor
(fallback devreye girdi)" mesajı almaya başladı - durum FAILED.
Account Service tekrar açılıp 10 saniye beklendikten sonra yapılan
transfer COMPLETED olarak başarıyla tamamlandı.

**Tasarım notu**: Fallback sınıfı RuntimeException fırlattığı için,
TransactionService'teki hata yönetimine ayrı bir catch (RuntimeException e)
bloğu eklendi - bu, fallback mesajının olduğu gibi (üzerine ekstra
metin eklenmeden) kullanıcıya iletilmesini sağladı.

## Retry Mekanizması (Spring Retry)

Transaction Service'in Account Service'e yaptığı withdraw çağrısına
@Retryable eklendi. Sadece bağlantı/IO hatalarında (ConnectException,
IOException) en fazla 3 deneme, denemeler arası 500ms bekleme ile
retry yapılıyor. İş mantığı hataları (yetersiz bakiye gibi) retry
kapsamına alınmadı, çünkü bunlar tekrar denemekle çözülmez.

**Tasarım kararı**: Resilience4j'nin kendi Retry modülü yerine Spring
Retry (@Retryable) tercih edildi — Circuit Breaker ile aynı çağrı
üzerinde doğru sırada çalışmasını garanti etmek, ek karmaşık
yapılandırma gerektiriyordu. Spring Retry, Circuit Breaker'dan
bağımsız, daha basit bir katman olarak eklendi.

**Test ile doğrulandı**: Account Service kapatılıp transfer denendiğinde,
cevabın gelmesi normalden çok daha uzun sürdü (birkaç saniye ile 10
saniye arasında değişen sürelerde) — bu, hem Retry'ın birden fazla
deneme yaptığının hem de bazı durumlarda Circuit Breaker'ın
wait-duration-in-open-state süresine denk geldiğinin işareti. Account
Service tekrar açıldığında transferler hızlı (1-2 saniye) ve
COMPLETED olarak tamamlandı.

## Rate Limiter (Gateway Seviyesinde)

Gateway'e, tüm isteklere uygulanan global bir Rate Limiter eklendi
(GlobalFilter olarak, JWT filtresinden bile önce çalışacak şekilde
sıralandı - getOrder() = -2). 10 saniyede en fazla 10 istek kabul
ediliyor, aşımda 429 Too Many Requests dönüyor.

**Karşılaşılan sorun**: Gateway'e Resilience4j eklerken ilk denemede
doğrudan io.github.resilience4j (resilience4j-spring-boot3,
resilience4j-reactor) paketleri kullanılmaya çalışıldı, versiyon
numaraları (2.1.0, 2.2.0) Maven Central'da bulunamadı. Çözüm:
Transaction Service'te zaten sorunsuz çalıştığı bilinen
spring-cloud-starter-circuitbreaker-resilience4j paketine geçildi -
bu paket Spring Cloud BOM tarafından yönetildiği için versiyon
belirtmeye gerek kalmadı, ve içindeki RateLimiter sınıfları
(io.github.resilience4j.ratelimiter.*) koda hiç değişiklik
gerektirmeden kullanılabildi.

**Test ile doğrulandı**: Art arda hızlıca istek gönderildiğinde,
belirli bir noktadan sonra (tam sayı, isteklerin gönderilme hızına
göre değişse de) 429 alındı - bu, manuel/elle test etmenin doğal bir
sınırlaması (network/tıklama zamanlaması tam saniyeye kilitlenemiyor).
Mekanizmanın kendisinin doğru çalıştığı, sınırın gerçekten
uygulandığı doğrulandı.