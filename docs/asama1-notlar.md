# Aşama 1 — Öğrenilenler ve Karşılaşılan Sorunlar

## Karşılaşılan Sorunlar ve Çözümleri

### 1. Spring Boot / Spring Cloud Versiyon Uyumsuzluğu
Spring Initializr'da Boot versiyonu elle seçilmezse çok yeni (deneysel)
bir versiyon gelebiliyor, Spring Cloud BOM ile uyumsuzluk çıkarıyor.
Hata: `dependencies.dependency.version ... is missing`
Çözüm: Boot 3.3.4 + Spring Cloud 2023.0.3 kombinasyonuna sabitlendi,
tüm servislerde tutarlı tutuldu.

### 2. Yanlış Yazılmış Dependency İsimleri
`spring-boot-starter-webmvc` gibi var olmayan bir paket ismi yazılmıştı
(doğrusu `spring-boot-starter-web`). Ayrıca her starter için ayrı
"-test" paketi diye bir şey yok, tek bir `spring-boot-starter-test`
yeterli. Var olmayan bir paket ismi, versiyon bulunamıyor hatasıyla
aynı şekilde görünüyor — bu ayrımı yapmak biraz zaman aldı.

### 3. PostgreSQL Port Çakışması (Windows)
Windows'ta native kurulu bir PostgreSQL servisi (postgres.exe), Docker'ın
PostgreSQL container'ıyla aynı portu (5432) paylaşınca
"password authentication failed" hatası çıktı — şifreler doğruydu ama
istekler yanlış PostgreSQL'e gidiyordu.
Tespit: `netstat -ano | findstr :5432` ve `tasklist /FI "PID eq ..."`
Çözüm: Native PostgreSQL servisi Windows Services'ten durduruldu.

### 4. IntelliJ'de Yanlış Paket Konumu
Yeni paket oluştururken (repository, config), sağ tıklanan klasörün
yanlış seçilmesi sonucu paketler `com.banking.auth`'un içine değil,
`java` klasörünün altına kardeş paket olarak oluştu. Bu, hem IDE'de
hem derlemede "cannot resolve symbol" tarzı hatalara yol açtı.
Çözüm: Paket, doğru üst dizine sürükle-bırak ile taşındı / silinip
doğru yerden tekrar oluşturuldu.

### 5. IntelliJ'nin Maven Modülünü Tanımaması
`pom.xml` düzenlendikten sonra IntelliJ, projeyi Maven modülü olarak
göstermeye devam etmedi (External Libraries boş kaldı), oysa terminal
üzerinden `mvnw spring-boot:run` sorunsuz çalışıyordu.
Çözüm: pom.xml'e sağ tık > "Add as Maven Project"

### 6. Docker Container'larının Kapanması
Bilgisayar kapatılıp açıldığında Docker container'ları (postgres,
kafka, zookeeper) otomatik durdu, bir sonraki oturumda "Unable to
determine Dialect without JDBC metadata" gibi bağlantı hatası çıktı.
Çözüm: Her çalışma seansı başında `docker ps` ile kontrol, gerekirse
`docker compose up -d` ile yeniden başlatma alışkanlığı edinildi.

### 7. Gateway'de Route Çakışması
Hem otomatik discovery locator hem elle tanımlı route aynı anda
aktifken, `/auth-service/api/auth/ping` beklenirken sadece
`/api/auth/ping` (elle tanımlı route'un predicate'ine uygun) çalıştı.
Not: Bu, 2. aşamada tüm servisler eklenirken tutarlı bir route
yapısıyla yeniden düzenlenecek.

## Bu Aşamada Öğrenilenler

- Mikroservis mimarisinde her servisin kendi veritabanı olması gerekliliği
- Eureka ile service discovery mantığı
- Spring Cloud Gateway'in route mekanizmaları (elle tanımlı vs. otomatik keşif)
- Maven BOM (dependencyManagement) kavramı ve neden gerekli olduğu
- Windows'ta port çakışmalarını netstat/tasklist ile teşhis etme
- Java paket yapısı ile fiziksel klasör yapısının birebir eşleşmesi gerekliliği

## 2. Aşamaya Not

- Gateway'deki route yapısı düzenlenecek: her yeni servis eklendikçe
  hem elle tanımlı route hem otomatik discovery locator karışıklık
  yaratabiliyor. Tüm servisler eklendiğinde, path yapısını tutarlı
  hale getirip (örn. `/api/{servis-adi}/**`) netleştireceğiz.