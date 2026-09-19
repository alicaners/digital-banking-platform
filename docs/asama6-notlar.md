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
bu proxy devreye girmiyor. Bu davranışların gerçek testi, Integration
testlerle (gerçek Spring context içinde) yapılabilir.

## Integration Test (Testcontainers)

Account Service için, gerçek bir PostgreSQL container'ı üzerinde
çalışan bir Integration test (AccountServiceIntegrationTest) yazıldı.
İki senaryo test edildi: hesap açma/sorgulama, ve para yatırma/
çekmenin veritabanında kalıcı olarak doğru şekilde saklanması.
Testcontainers, @Container ve @DynamicPropertySource ile testin
kendi geçici PostgreSQL container'ını otomatik ayağa kaldırıp
bağlantı bilgilerini Spring'e enjekte ediyor.

**Karşılaşılan sorun (uzun süren bir teşhis süreci)**: Testcontainers,
Docker Desktop'a bağlanamıyordu ("Could not find a valid Docker
environment"), hem npipe hem TCP (localhost:2375) yöntemleri boş/
kısıtlı cevaplar alıyordu. Denenen adımlar: Docker Desktop'ı güncelleme,
IntelliJ'yi yönetici olarak çalıştırma, DOCKER_HOST ortam değişkenini
farklı formatlarda deneme, Testcontainers BOM versiyonunu güncelleme.
Sorun, bilgisayarın tam olarak yeniden başlatılmasıyla çözüldü -
muhtemelen güncelleme sonrası bazı Windows/WSL2 network bileşenlerinin
tam olarak yeniden başlatılması gerekiyordu.

**İkinci küçük sorun**: BigDecimal.equals() ile karşılaştırma
(BigDecimal.ZERO.setScale(2) vs veritabanından dönen değer) ölçek
farkı yüzünden başarısız oldu. Çözüm: assertEquals yerine
BigDecimal.compareTo() kullanıldı - bu, AccountService.withdraw()'daki
bakiye kontrolünde de kullandığımız, BigDecimal karşılaştırmasının
doğru yöntemi.

**Ders**: Testcontainers gibi Docker'a bağımlı araçlar, yerel
geliştirme ortamında (özellikle Windows) bazen uzun süren ortam
sorunlarına yol açabiliyor. Kod doğru olsa bile, altyapı (Docker
Desktop sürümü, network yapılandırması) sorunun kaynağı olabiliyor -
bu durumda sistematik olarak (bağlantı yöntemi, versiyon, yeniden
başlatma) her olasılığı elemek gerekiyor.

## Swagger/OpenAPI (Merkezi Gateway Üzerinden)

Auth, Customer, Account, Transaction ve Notification Service'lere
springdoc-openapi-starter-webmvc-ui eklendi. Gateway'e ise
springdoc-openapi-starter-webflux-ui eklendi ve springdoc.swagger-ui.urls
ayarı ile beş servisin API tanımları tek bir merkezi Swagger sayfasında
(http://localhost:8080/swagger-ui.html) toplandı - kullanıcı sağ üstteki
dropdown'dan istediği servisi seçip API'sini inceleyebiliyor.

Bunun için Gateway'e her servisin /v3/api-docs adresine giden özel
route'lar eklendi (RewritePath filtresiyle), ve bu dokümantasyon
adresleri JwtAuthenticationFilter'ın openEndpoints listesine eklenerek
token gerektirmeden erişilebilir hale getirildi.

**Karşılaşılan sorun**: Swagger sayfasındaki "Servers" alanı,
Springdoc'un otomatik tahmin ettiği Docker/WSL2 iç network adresini
(örn. 172.24.160.1) gösteriyordu, gerçek localhost adresini değil.
Çözüm: her servise bir OpenApiConfig sınıfı eklenip, OpenAPI bean'i
içinde Server nesnesi ile doğru localhost:PORT adresi elle belirtildi.

**Test ile doğrulandı**: Merkezi Swagger sayfasında beş servis de
dropdown'da listelendi, her biri seçildiğinde hem doğru endpoint'ler
hem de doğru Servers adresi (localhost üzerinden) görüntülendi.

**Bilinçli kapsam kararı**: Eureka Server'a Swagger eklenmedi, çünkü
kendi @RestController'ı yok, pratik faydası olmayacaktı.

## Sonraki Adımlar (Planlanan, Henüz Yapılmadı)
- Gün 3: Mimari diyagram, README toparlanması, faz kapanışı