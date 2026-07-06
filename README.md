# Jasmin Android Server

Android phone'u SMS gateway'e ceviren HTTP API + SMPP server uygulamasi.

Jasmin SMS gateway'in Android karsiligi gibi dusunebilirsiniz - dogrudan telefonunuzdan SMS gonderebilirsiniz.

## Ozellikler

### HTTP API
- `POST /api/send` - Tek SMS gonder
- `POST /api/send-bulk` - Toplu SMS gonder
- `GET /api/status` - Sunucu durumu
- `GET /api/report/{id}` - SMS raporu (report ID ile)
- `GET /api/reports` - Tum raporlar
- `GET /api/logs` - SMS loglari
- `GET /api/info` - Cihaz/SIM/ag bilgisi
- `GET /api/contacts` - Kisi listesi
- `POST /api/contacts` - Kisi ekle
- `DELETE /api/contacts/{phone}` - Kisi sil

### SMPP Server
- `POST /api/smpp/start` - SMPP sunucusunu baslat
- `POST /api/smpp/stop` - SMPP sunucusunu durdur
- `GET /api/smpp/status` - SMPP durumu

SMPP server port 2775'te dinler. Varsayilan credentials:
- systemId: `smsapi`
- password: `password`

### Desteklenen SMPP Komutlari
- `BIND_TRANSMITTER` / `BIND_RECEIVER` / `BIND_TRANSCEIVER`
- `SUBMIT_SM` - SMS gonder
- `ENQUIRE_LINK` - Baglanti kontrolu
- `UNBIND` - Baglantiyi kes

## Kurulum

1. Android Studio ile projeyi acin
2. `minSdk 26+` bir cihaza yukleyin
3. Uygulamayi acin ve "Server Start" butonuna basin
4. HTTP API: `http://<telefon-IP>:8080`
5. SMPP: `<telefon-IP>:2775`

## Ornek: HTTP API ile SMS

```bash
curl -X POST http://192.168.9.10:8080/api/send \
  -H "Content-Type: application/json" \
  -d '{"phone":"905441497005","message":"Merhaba Dunya!"}'
```

## Ornek: SMPP Client ile SMS

```python
import socket, struct

def write_cstring(s):
    return s.encode('ascii') + b'\x00'

sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
sock.connect(('192.168.9.10', 2775))

# BIND
body = write_cstring('smsapi') + write_cstring('password') + write_cstring('') + b'\x34'
pdu = struct.pack('>IIII', 16+len(body), 0x00000002, 0, 1) + body
sock.sendall(pdu)
sock.recv(1024)

# SUBMIT_SM
msg = "Merhaba Dunya!"
body = (write_cstring('') + b'\x00\x00' + write_cstring('9050000000') +
        b'\x01\x01' + write_cstring('905441497005') +
        b'\x00\x00\x00' + write_cstring('') + write_cstring('') +
        b'\x01\x00\x00\x00' + bytes([len(msg)]) + msg.encode())
pdu = struct.pack('>IIII', 16+len(body), 0x00000004, 0, 2) + body
sock.sendall(pdu)
resp = sock.recv(1024)

sock.close()
```

## Rapor Sistemi

Her SMS bir report ID alir (`RPT-XXXXXXXX`). Bu ID ile SMS'in durumunu takip edebilirsiniz:
- `PENDING` - Gonderiliyor
- `SENT` - GSM agina verildi
- `DELIVERED` - Aliciya ulasti
- `FAILED` - Basarisiz

## Kisi Sistemi

Telefon numaralarina isim verebilirsiniz. SMS gonderirken kisi ismi otomatik olarak rapora eklenir.

```bash
# Kisi ekle
curl -X POST http://192.168.9.10:8080/api/contacts \
  -H "Content-Type: application/json" \
  -d '{"phone":"905441497005","name":"Ahmet","group":"dostlar"}'
```

## Gereksinimler

- Android 8.0+ (API 26)
- Root erisimi (ag durumu icin)
- SMS izni

## Lisans

MIT
