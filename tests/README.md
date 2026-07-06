# SMPP Server Tests

Python test suite for Jasmin Android Server's SMPP and HTTP API.

## Requirements

- Python 3.6+
- No external dependencies (stdlib only)
- Phone connected and app running

## Quick Start

```bash
# Edit PHONE_IP in test file if needed
vim tests/test_smpp.py

# Run all tests
python3 tests/test_smpp.py
```

## Configuration

Edit the top of `test_smpp.py`:

```python
PHONE_IP = "192.168.9.10"    # Your phone's IP
SMPP_PORT = 2775              # SMPP server port
HTTP_PORT = 8080              # HTTP API port
SYSTEM_ID = "smsapi"          # SMPP system ID
PASSWORD = "password"         # SMPP password
DEST_PHONE = "905441497005"   # Destination phone number
SOURCE_PHONE = "9050000000"   # Source phone number
```

## Test Coverage

### SMPP Protocol
| Test | Description |
|------|-------------|
| BIND_TRANSMITTER | Transmitter bind authentication |
| BIND_RECEIVER | Receiver bind authentication |
| BIND_TRANSCEIVER | Transceiver bind authentication |
| BIND wrong creds | Reject invalid credentials |
| SUBMIT_SM ASCII | GSM 7bit message |
| SUBMIT_SM UCS2 | Unicode message |
| SUBMIT_SM registered | Delivery report request |
| SUBMIT_SM long | Multipart/concatenated SMS |
| ENQUIRE_LINK | Link keepalive |
| DELIVER_SM | Delivery report to receiver |
| UNBIND | Session disconnect |

### HTTP API
| Test | Description |
|------|-------------|
| /api/status | Server status |
| /api/info | Device/SIM/network info |
| /api/send | Send SMS |
| /api/report/{id} | Get report |
| /api/reports | List all reports |
| /api/contacts | CRUD contacts |

## Example Output

```
============================================================
SMPP Android Server - Test Suite
SMPP: 192.168.9.10:2775
HTTP: 192.168.9.10:8080
============================================================

==================================================
TEST: HTTP Server Status
==================================================
  ✓ HTTP running: True
  ✓ Port: 8080

==================================================
TEST: BIND_TRANSMITTER
==================================================
  ✓ Connected to SMPP server
  ✓ BIND success (status=0)

==================================================
TEST: DELIVER_SM (delivery report)
==================================================
  ✓ BIND_RECEIVER success
  ✓ SMS sent via HTTP: RPT-12345678
  ✓ Received DELIVER_SM (cmd_id=0x00000005)
  ✓ Sent DELIVER_SM_RESP

============================================================
RESULTS: 35 passed, 0 failed
============================================================
```

## Exit Codes

- `0` - All tests passed
- `1` - One or more tests failed
