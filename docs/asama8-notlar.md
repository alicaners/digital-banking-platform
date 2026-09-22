# Aşama 8 — Hardening (Sağlamlaştırma)

## Gün 1 — Konu A: Hızlı Güvenlik Düzeltmeleri

Bir kod incelemesi (review) sonrası tespit edilen üç kritik güvenlik
açığı giderildi:

1. **JWT secret varsayılan değeri kaldırıldı**: auth-service ve
   api-gateway'de `${JWT_SECRET:varsayılan...}` yerine `${JWT_SECRET}`
   kullanıldı - artık gerçek bir .env dosyası olmadan uygulama hiç
   başlamıyor, kaynak kodda sabit bir secret kalmadı.

2. **Servis bypass'ı engellendi**: docker-compose.yml'de sadece
   api-gateway'in portu (8080) dışarı açık bırakıldı, diğer altı
   uygulama servisinin portları kaldırıldı - artık
   http://localhost:8083/api/accounts gibi doğrudan servis erişimi
   mümkün değil, her istek zorunlu olarak Gateway üzerinden (ve
   dolayısıyla JWT kontrolünden) geçiyor.

3. **docker-compose.yml ile README arasındaki tutarsızlık giderildi**:
   Her uygulama servisine `build: context:` eklendi - artık
   `docker compose up -d --build` gerçekten Dockerfile'lardan sıfırdan
   inşa edebiliyor, önceden elle `docker build` yapmak şart değil.

**Test ile doğrulandı**: Gateway üzerinden (localhost:8080) istekler
normal çalışmaya devam etti (`POST /api/auth/login` doğru hata mesajı
döndürdü). Doğrudan servis portlarına yapılan istek
(`curl http://localhost:8083/api/accounts`) "Failed to connect"
hatasıyla reddedildi - servis artık host makineden erişilemiyor,
sadece Docker network'ü içinden.