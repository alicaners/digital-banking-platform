# Yük Testleri (k6)

Gateway üzerinden uçtan uca çalışan [k6](https://k6.io) senaryoları.

## Gereksinimler
- k6 (`winget install k6 --source winget`, kurulumdan sonra yeni bir terminal aç)
- `docker compose up -d` ile ayağa kalkmış tüm servisler

## Dosyalar
- `transfer-load-test.js`: taban çizgisi (baseline) senaryosu
- `circuit-breaker-load-test.js`: dayanıklılık senaryosu
- `lib/common.js`: iki senaryonun ortak kodu (HTTP yardımcıları, test verisi hazırlama)
- `results/`: örnek çalıştırma çıktıları

Her çalıştırma kendi test verisini (kullanıcı, müşteri, iki hesap, bakiye) kurar,
bu yüzden tekrar tekrar çalıştırılabilir.

## Senaryolar

### 1. Taban çizgisi — `transfer-load-test.js`
Tek sanal kullanıcıyla 1 dakika boyunca sürekli transfer.

```
k6 run load-tests/transfer-load-test.js
```

Eşikler (thresholds):
- transfer isteklerinin p95 süresi < 500 ms
- transfer HTTP hata oranı < %1
- check başarı oranı > %99

Örnek sonuç (`results/transfer-baseline.txt`): 48 transferin tamamı `COMPLETED`,
transfer p95 ≈ 70 ms, tüm eşikler geçti.

### 2. Circuit breaker — `circuit-breaker-load-test.js`
4 dakika boyunca sürekli transfer. Test sürerken Account Service elle durdurulup
başlatılır.

Terminal 1:
```
k6 run load-tests/circuit-breaker-load-test.js
```

Terminal 2 (testin yaklaşık 20. saniyesinde durdur, ~2 dakika sonra başlat):
```
docker stop banking-account-service
docker start banking-account-service
```

Her transferin süresi `[Ns] OK/HATA ...` satırlarıyla yazdırılır; süreden devre
durumu okunur:
- ~1000 ms hata: 3 deneme + 2 × 500 ms bekleme (CLOSED veya HALF-OPEN)
- 20–80 ms hata: circuit OPEN, çağrı yapılmadan hızlı red
- servis döndükten sonra ~40–65 ms OK: circuit tekrar CLOSED

Örnek sonuç (`results/circuit-breaker-run.txt`): 23. saniyeden itibaren yavaş
hatalar, 28. saniyede circuit OPEN, yaklaşık 10 saniyelik OPEN / HALF-OPEN
döngüleri, 136. saniyede servis dönünce kurtarma.

## Neden tek sanal kullanıcı ve 1.2 sn bekleme?
Gateway'de global bir rate limiter vardır (10 saniyede 10 istek). Daha hızlı veya
çok kullanıcılı bir test, iş mantığı yerine 429 yanıtlarını ölçerdi. Bu testlerin
amacı sistemin kapasitesini zorlamak değil, normal hızda gecikmeyi ve kesinti
davranışını doğrulamaktır.

## `http_req_failed` neden %0 görünüyor?
Transfer iş hataları (servis erişilemez, bakiye yetersiz vb.) HTTP 200 ve
`status: FAILED` olarak döner. k6 bunları HTTP hatası saymaz; başarısızlıklar
`transfers_failed` sayacından ve log satırlarından izlenir.

## Farklı bir adrese karşı çalıştırma
```
k6 run -e BASE_URL=http://baska-adres:8080 load-tests/transfer-load-test.js
```