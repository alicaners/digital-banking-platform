# Aşama 6 — Öğrenilenler ve Karşılaşılan Sorunlar

## Unit Testler (Mockito)

Auth Service, Account Service ve Transaction Service için Mockito
tabanlı Unit testler yazıldı. Bu üç servis, en çok iş mantığı
içerdiği için öncelikli olarak seçildi; Customer Service (basit CRUD)
ve altyapı servisleri (Eureka, Gateway, Notification) bu fazın
kapsamı dışında bırakıldı.

**Toplam 15 test yazıldı:**
- Auth Service (6): register (başarılı, kullanıcı adı çakışması,
  email çakışması), login (başarılı, kullanıcı bulunamadı, yanlış şifre)
- Account Service (5): deposit (başarılı, negatif miktar), withdraw
  (başarılı, yetersiz bakiye), getAccountById (hesap bulunamadı)
- Transaction Service (4): transfer'in dört Saga senaryosu (başarılı
  COMPLETED, withdraw hatası FAILED, deposit hatası + başarılı
  telafi REVERSED, deposit + telafi ikisi de başarısız FAILED)

**Karşılaşılan sorun**: İlk test çalıştırıldığında Mockito, "Java 25
is not supported by the current version of Byte Buddy which
officially supports Java 23" hatası verdi. Sebep: IntelliJ'nin proje
ayarlarında "Language level" 25 olarak seçiliydi, oysa proje SDK'sı
Java 21'di. Çözüm: File > Project Structure > Language level, 21
olarak düzeltildi. Bu, kodun kendisiyle ilgili değil, IDE/JVM
yapılandırma uyumsuzluğuyla ilgili bir sorundu.

**Test yaklaşımı (Arrange-Act-Assert)**: Her test üç aşamadan oluşuyor:
1. Mock nesnelerin davranışı `when(...).thenReturn(...)` ile tanımlanıyor
2. Gerçek servis metodu çağrılıyor
3. Sonuç `assertEquals`/`assertThrows` ile, yan etkiler ise
   `verify(...)` ile doğrulanıyor (örn. "save() hiç çağrılmadı mı")

**Not**: `@Retryable` ve `@Cacheable`/`@CacheEvict` gibi annotation'lar,
Unit testlerde etkisiz kalıyor çünkü bunlar Spring'in AOP proxy
mekanizmasıyla çalışıyor - Mockito ile doğrudan çağrılan metodlarda
bu proxy devreye girmiyor. Bu davranışların gerçek testi, Aşama 6
Gün 2'de Integration testlerle (gerçek Spring context içinde) yapılacak.

## Sonraki Adımlar (Planlanan, Henüz Yapılmadı)
- Gün 2: Integration testler (Testcontainers ile gerçek PostgreSQL),
  Swagger/OpenAPI ile API dokümantasyonu
- Gün 3: Mimari diyagram, README toparlanması, faz kapanışı