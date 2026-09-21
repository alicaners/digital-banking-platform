# Aşama 7 — Öğrenilenler ve Karşılaşılan Sorunlar

## Dockerfile'lar (Multi-Stage Build)

Yedi mikroservisin her birine, multi-stage build kullanan bir
Dockerfile eklendi. İlk aşama (maven:3.9-eclipse-temurin-21) projeyi
derleyip bir .jar üretiyor, ikinci aşama (eclipse-temurin:21-jre)
sadece bu .jar'ı çalıştırmak için gereken minimal Java runtime'ı
içeriyor - bu, final image boyutunu önemli ölçüde küçültüyor (her
servis 165-206MB aralığında kaldı) ve gereksiz build araçlarını
production image'ından çıkarıyor.

Her servise ayrıca .dockerignore eklendi (target/, .idea/, .git/
gibi klasörleri build context'inden hariç tutmak için).

**Test edildi**: Yedi servisin hepsi `docker build` ile başarıyla
image'a çevrildi (docker images ile doğrulandı). account-service tek
başına çalıştırıldığında (docker run), beklenen şekilde PostgreSQL'e
bağlanamadı ("Connection to localhost:5432 refused") - çünkü container
kendi izole ağında çalışıyor, senin bilgisayarındaki localhost'u
göremiyor. Bu, Aşama 7 Gün 2'de docker-compose.yml ile tüm servisler
aynı Docker network'üne alınarak çözülecek.

## docker-compose.yml ile Tüm Sistemi Tek Komutla Ayağa Kaldırmak

application.yml dosyalarındaki sabit "localhost" adresleri, ortam
değişkenleriyle (${DB_HOST:localhost}, ${REDIS_HOST:localhost},
${KAFKA_HOST:localhost}, ${EUREKA_HOST:localhost}) esnek hale getirildi
- varsayılan değer localhost kaldığı için IntelliJ'den elle çalıştırma
  da bozulmadı. docker-compose.yml'e yedi uygulama servisi eklendi,
  hepsi ortak bir "banking-network" adlı Docker network'üne alındı,
  container isimleri (postgres, kafka, eureka-server vb.) üzerinden
  birbirleriyle konuşacak şekilde ortam değişkenleri tanımlandı.

**Karşılaşılan sorun (uzun ve karmaşık bir teşhis süreci)**:
`docker compose up -d` ile tüm sistem ayağa kalktıktan sonra,
transaction-service beklenmedik şekilde çöktü (Exited (1),
"Connection to localhost:5432 refused" - application.yml
güncellemesi image'a yansımamıştı). Yeniden build edildiğinde
Docker'ın "CACHED" katmanı kullanması yüzünden ilk denemede yine
eski koddan build etti; `--no-cache` ile zorla sıfırdan build
edilerek çözüldü.

Daha ilginç bir ikinci sorun: account-service container'ı "Up"
görünmesine rağmen, docker logs komutu ısrarla transaction-service'in
loglarını gösteriyordu, ve container içinden yapılan testler
(wget, curl) "Connection refused" veriyordu (uygulama 8083 portunda
hiç dinlemiyordu). docker inspect ile incelendiğinde, account-service
ve transaction-service image'larının birebir aynı boyutta (645MB)
olduğu görüldü - account-service:latest etiketi, yanlışlıkla (muhtemelen
önceki bir build sırasında yanlış dizinde çalıştırılan komuttan)
transaction-service'in image'ına işaret ediyordu. Çözüm: eski image
(docker rmi) tamamen silindi, doğru dizinde (account-service/) tekrar
--no-cache ile build edildi - bu sefer doğru boyutta (191MB, 645MB
değil) bir image üretildi ve container doğru uygulamayı çalıştırdı.

Sürecin ortasında ayrıca geçici bir internet/DNS kesintisi de build'i
bir kez daha başarısız kıldı ("no such host" hatası) - wifi
değiştirilerek çözüldü, kod veya Docker yapılandırmasıyla ilgisizdi.

**Ders**: Docker image etiketleri (tag), hangi dizinde build
komutunun çalıştırıldığına tamamen bağımlı - yanlış dizinde
`docker build -t X:latest .` çalıştırmak, X etiketinin başka bir
servisin image'ına işaret etmesine yol açabilir, ve bu hata Docker
tarafından hiçbir uyarı vermeden sessizce kabul edilir. Şüpheli bir
durumda (loglar beklenmedik içerik gösteriyorsa, image boyutları
farklı servislerde aynıysa) `docker inspect --format="{{.Id}}"` ile
image ID'lerini karşılaştırmak, gerçek sebebi hızlıca ortaya çıkarıyor.

**Test ile doğrulandı**: docker compose up -d ile 11 container
(4 altyapı + 7 uygulama) başarıyla ayağa kalktı, Eureka dashboard'unda
6 servisin hepsi (Eureka hariç) UP olarak listelendi. Gateway üzerinden
(http://localhost:8080/api/auth/login) yapılan uçtan uca test 200 OK
ve geçerli bir JWT token döndü - sistem artık IntelliJ'ye hiç ihtiyaç
duymadan, sadece Docker ile tam olarak çalışıyor.

## Sonraki Adımlar (Planlanan, Henüz Yapılmadı)
- Gün 3: GitHub Actions ile CI pipeline, faz kapanışı