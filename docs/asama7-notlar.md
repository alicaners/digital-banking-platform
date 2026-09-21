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

## Sonraki Adımlar (Planlanan, Henüz Yapılmadı)
- Gün 2: docker-compose.yml'e uygulama servislerini ekleyip tüm
  sistemi tek komutla ayağa kaldırma
- Gün 3: GitHub Actions ile CI pipeline, faz kapanışı