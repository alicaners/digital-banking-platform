# Postman Koleksiyonu

Gateway (`http://localhost:8080`) üzerinden uçtan uca çalışan bir senaryo:
kayıt, giriş, müşteri ve hesap oluşturma, transfer ve refresh token / logout
akışı. Her istekte doğrulamalar (Tests sekmesi) tanımlıdır.

## Dosyalar
- `digital-banking-platform.postman_collection.json`: koleksiyon (17 istek, 32 test)
- `banking-local.postman_environment.json`: "Banking Local" ortamı (`baseUrl` ve
  scriptlerin doldurduğu değişkenler; içinde gizli bilgi yoktur)

## Kullanım
1. Sistemi başlat: `docker compose up -d` (bkz. kök README).
2. Postman'de **Import** ile iki dosyayı da içeri al.
3. Sağ üstteki ortam seçiciden **Banking Local**'ı seç.
4. Koleksiyonun **⋯** menüsünden **Run collection** → **Delay: 1200 ms** →
   çalıştır. Gateway'deki rate limiter 10 saniyede 10 isteğe izin verdiği için
   gecikme gereklidir; yoksa istekler `429` döner.

## Akış
1. **Auth - Kayıt ve giriş:** Register (her çalıştırmada yeni kullanıcı adı
   üretilir), Login (`token` ve `refreshToken` ortama otomatik yazılır; sonraki
   isteklerin `Authorization` başlığı `{{accessToken}}` ile dolar).
2. **Customers / Accounts:** müşteri, iki hesap, gönderen hesaba para yatırma.
3. **Transactions:** transfer (her gönderimde yeni `Idempotency-Key`) ve aynı
   anahtarla tekrar gönderim (önceki sonuç döner, bakiye ikinci kez düşmez).
4. **Auth - Oturum yönetimi:** refresh token rotation, eski token ile tekrar
   deneme (401, kullanıcının tüm refresh token'ları iptal edilir), yeniden giriş,
   logout (204), logout sonrası refresh (401), logout'un tekrarlanabilirliği.

Ayrıntılar için bkz. `docs/asama8-notlar.md`, "Refresh Token ve Logout".