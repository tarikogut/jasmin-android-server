# Jasmin Android Server

Turn your Android phone into an SMS gateway with HTTP API + SMPP server.

Think of it as an Android counterpart to Jasmin SMS gateway - send SMS directly from your phone.

## Features

### HTTP API
- `POST /api/send` - Send a single SMS
- `POST /api/send-bulk` - Send bulk SMS
- `GET /api/status` - Server status
- `GET /api/report/{id}` - SMS report by report ID
- `GET /api/reports` - All reports
- `GET /api/logs` - SMS logs
- `GET /api/info` - Device/SIM/network info
- `GET /api/contacts` - Contact list
- `POST /api/contacts` - Add contact
- `DELETE /api/contacts/{phone}` - Delete contact

### SMPP Server
- `POST /api/smpp/start` - Start SMPP server
- `POST /api/smpp/stop` - Stop SMPP server
- `GET /api/smpp/status` - SMPP status

SMPP server listens on port 2775. Default credentials:
- systemId: `smsapi`
- password: `password`

### Supported SMPP Commands
- `BIND_TRANSMITTER` / `BIND_RECEIVER` / `BIND_TRANSCEIVER`
- `SUBMIT_SM` - Send SMS
- `ENQUIRE_LINK` - Link keepalive
- `UNBIND` - Disconnect

## Setup

1. Open the project in Android Studio
2. Install on a device with `minSdk 26+`
3. Launch the app and tap "Server Start"
4. HTTP API: `http://<phone-ip>:8080`
5. SMPP: `<phone-ip>:2775`

## Example: Send SMS via HTTP API

```bash
curl -X POST http://192.168.9.10:8080/api/send \
  -H "Content-Type: application/json" \
  -d '{"phone":"905441497005","message":"Hello World!"}'
```

## Example: Send SMS via SMPP Client

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
msg = "Hello World!"
body = (write_cstring('') + b'\x00\x00' + write_cstring('9050000000') +
        b'\x01\x01' + write_cstring('905441497005') +
        b'\x00\x00\x00' + write_cstring('') + write_cstring('') +
        b'\x01\x00\x00\x00' + bytes([len(msg)]) + msg.encode())
pdu = struct.pack('>IIII', 16+len(body), 0x00000004, 0, 2) + body
sock.sendall(pdu)
resp = sock.recv(1024)

sock.close()
```

## Report System

Every SMS gets a report ID (`RPT-XXXXXXXX`). Track SMS status with this ID:
- `PENDING` - Being sent
- `SENT` - Handed to GSM network
- `DELIVERED` - Reached the recipient
- `FAILED` - Failed

## Contact System

Name your phone numbers. Contact names are automatically attached to SMS reports.

```bash
# Add a contact
curl -X POST http://192.168.9.10:8080/api/contacts \
  -H "Content-Type: application/json" \
  -d '{"phone":"905441497005","name":"John","group":"friends"}'
```

## Requirements

- Android 8.0+ (API 26)
- Root access (for network status)
- SMS permission

## License

MIT
