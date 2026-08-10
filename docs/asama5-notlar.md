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