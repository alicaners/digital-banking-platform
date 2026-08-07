# Aşama 4 — Öğrenilenler ve Karşılaşılan Sorunlar

## Saga Pattern Uygulaması (Compensating Transaction)

Aşama 3'te tespit edilen distributed transaction problemi çözüldü.
transfer() metoduna, deposit aşamasında hata oluşursa withdraw
işlemini geri alan (compensate) bir mekanizma eklendi.

**Test ile doğrulandı**: Hesap 1'in bakiyesi 480 TL'ydi, geçersiz
bir hesaba (9999) 50 TL transfer denendi. İşlem "REVERSED" olarak
işaretlendi ve hesap 1'in bakiyesi tekrar 480 TL'ye döndü — Aşama 3'te
para kalıcı olarak kaybolurken, artık güvenli şekilde geri alınıyor.

**Yeni transaction durumları:**
- COMPLETED: her iki adım da başarılı
- FAILED: withdraw'ın kendisi başarısız oldu (para hiç hareket etmedi)
- REVERSED: withdraw başarılı oldu ama deposit başarısız oldu,
  telafi (geri yatırma) başarıyla yapıldı

**Bilinen sınırlama**: Eğer telafi işleminin kendisi de başarısız
olursa (örn. Account Service o an erişilemezse), durum yine FAILED
olarak işaretleniyor ama bu sefer para gerçekten tutarsız bir
durumda kalabilir. Gerçek prodüksiyon sistemlerinde bu, bir "dead
letter queue" veya manuel müdahale süreciyle çözülür — bu projenin
kapsamı dışında bırakıldı.