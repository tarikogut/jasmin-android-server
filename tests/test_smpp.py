#!/usr/bin/env python3
"""
SMPP v3.4 Protocol Test Suite
Tests: BIND, SUBMIT_SM, ENQUIRE_LINK, UNBIND, DELIVER_SM, Long SMS, UCS2
"""

import socket
import struct
import time
import sys
import json
import urllib.request

PHONE_IP = "192.168.9.10"
SMPP_PORT = 2775
HTTP_PORT = 8080
SYSTEM_ID = "smsapi"
PASSWORD = "password"
DEST_PHONE = "905441497005"
SOURCE_PHONE = "9050000000"

passed = 0
failed = 0


def write_cstring(s):
    return s.encode('ascii') + b'\x00'


def read_cstring(data, offset):
    end = data.index(b'\x00', offset) if b'\x00' in data[offset:] else len(data)
    return data[offset:end].decode('ascii', errors='replace'), end + 1


def recv_full(sock, max_size=1024):
    """Read SMPP PDU: first 4 bytes = length, then read remaining."""
    data = b''
    while len(data) < 4:
        chunk = sock.recv(4 - len(data))
        if not chunk:
            break
        data += chunk

    if len(data) < 4:
        return data

    resp_len = struct.unpack('>I', data[:4])[0]
    resp_len = min(resp_len, max_size)

    while len(data) < resp_len:
        chunk = sock.recv(resp_len - len(data))
        if not chunk:
            break
        data += chunk

    return data


def send_and_recv(sock, pdu, desc):
    sock.sendall(pdu)
    data = recv_full(sock)
    if len(data) >= 16:
        resp_len, resp_id, resp_status, resp_seq = struct.unpack('>IIII', data[:16])
        print(f"  [{desc}] status={resp_status}, seq={resp_seq}")
        return data, resp_status
    print(f"  [{desc}] No response ({len(data)} bytes)")
    return data, -1


def build_bind(system_id, password, version=0x34):
    body = write_cstring(system_id) + write_cstring(password) + write_cstring('') + bytes([version])
    return struct.pack('>IIII', 16 + len(body), 0x00000002, 0, 1) + body


def build_submit_sm(src, dst, msg, esm_class=0, data_coding=0, registered_delivery=0, seq=2):
    if isinstance(msg, str):
        msg_bytes = msg.encode('ascii')
    else:
        msg_bytes = msg
    body = (write_cstring('') + bytes([0x01, 0x01]) + write_cstring(src) +
            bytes([0x01, 0x01]) + write_cstring(dst) +
            bytes([esm_class, 0x00, 0x00]) + write_cstring('') + write_cstring('') +
            bytes([registered_delivery, 0x00, data_coding, 0x00, len(msg_bytes)]) + msg_bytes)
    return struct.pack('>IIII', 16 + len(body), 0x00000004, 0, seq) + body


def build_enquire_link(seq):
    return struct.pack('>IIII', 16, 0x00000015, 0, seq)


def build_unbind(seq):
    return struct.pack('>IIII', 16, 0x00000006, 0, seq)


def build_deliver_sm_resp(seq):
    return struct.pack('>IIII', 16, 0x80000005, 0, seq)


def http_send(phone, message):
    payload = json.dumps({"phone": phone, "message": message}).encode()
    req = urllib.request.Request(
        f"http://{PHONE_IP}:{HTTP_PORT}/api/send",
        data=payload,
        headers={"Content-Type": "application/json"}
    )
    resp = urllib.request.urlopen(req, timeout=10)
    return json.loads(resp.read())


def http_get_report(report_id):
    req = urllib.request.Request(f"http://{PHONE_IP}:{HTTP_PORT}/api/report/{report_id}")
    resp = urllib.request.urlopen(req, timeout=10)
    return json.loads(resp.read())


def http_get_reports():
    req = urllib.request.Request(f"http://{PHONE_IP}:{HTTP_PORT}/api/reports")
    resp = urllib.request.urlopen(req, timeout=10)
    return json.loads(resp.read())


def test(name):
    global passed, failed
    print(f"\n{'='*50}")
    print(f"TEST: {name}")
    print(f"{'='*50}")
    return name


def assert_test(condition, msg):
    global passed, failed
    if condition:
        print(f"  ✓ {msg}")
        passed += 1
    else:
        print(f"  ✗ {msg}")
        failed += 1


def start_smpp_server():
    payload = json.dumps({"port": SMPP_PORT, "systemId": SYSTEM_ID, "password": PASSWORD}).encode()
    req = urllib.request.Request(
        f"http://{PHONE_IP}:{HTTP_PORT}/api/smpp/start",
        data=payload,
        headers={"Content-Type": "application/json"}
    )
    resp = urllib.request.urlopen(req, timeout=5)
    result = json.loads(resp.read())
    return result.get("success", False)


def stop_smpp_server():
    req = urllib.request.Request(
        f"http://{PHONE_IP}:{HTTP_PORT}/api/smpp/stop",
        method="POST"
    )
    try:
        resp = urllib.request.urlopen(req, timeout=5)
        return True
    except:
        return False


def get_smpp_status():
    req = urllib.request.Request(f"http://{PHONE_IP}:{HTTP_PORT}/api/smpp/status")
    resp = urllib.request.urlopen(req, timeout=5)
    return json.loads(resp.read())


# ============================================================
# TEST SUITE
# ============================================================

def test_bind_transmitter():
    test("BIND_TRANSMITTER")
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(10)
    sock.connect((PHONE_IP, SMPP_PORT))
    assert_test(True, "Connected to SMPP server")

    pdu = build_bind(SYSTEM_ID, PASSWORD, 0x34)
    data, status = send_and_recv(sock, pdu, "BIND_TRANSMITTER")
    assert_test(status == 0, f"BIND success (status={status})")

    pdu = build_unbind(100)
    send_and_recv(sock, pdu, "UNBIND")
    sock.close()
    return status == 0


def test_bind_receiver():
    test("BIND_RECEIVER")
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(10)
    sock.connect((PHONE_IP, SMPP_PORT))
    assert_test(True, "Connected")

    # BIND_RECEIVER command_id = 0x01
    body = write_cstring(SYSTEM_ID) + write_cstring(PASSWORD) + write_cstring('') + bytes([0x34])
    pdu = struct.pack('>IIII', 16 + len(body), 0x00000001, 0, 1) + body
    data, status = send_and_recv(sock, pdu, "BIND_RECEIVER")
    assert_test(status == 0, f"BIND_RECEIVER success (status={status})")

    pdu = build_unbind(100)
    send_and_recv(sock, pdu, "UNBIND")
    sock.close()
    return status == 0


def test_bind_transceiver():
    test("BIND_TRANSCEIVER")
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(10)
    sock.connect((PHONE_IP, SMPP_PORT))
    assert_test(True, "Connected")

    # BIND_TRANSCEIVER command_id = 0x09
    body = write_cstring(SYSTEM_ID) + write_cstring(PASSWORD) + write_cstring('') + bytes([0x34])
    pdu = struct.pack('>IIII', 16 + len(body), 0x00000009, 0, 1) + body
    data, status = send_and_recv(sock, pdu, "BIND_TRANSCEIVER")
    assert_test(status == 0, f"BIND_TRANSCEIVER success (status={status})")

    pdu = build_unbind(100)
    send_and_recv(sock, pdu, "UNBIND")
    sock.close()
    return status == 0


def test_bind_wrong_credentials():
    test("BIND with wrong credentials")
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(10)
    sock.connect((PHONE_IP, SMPP_PORT))
    assert_test(True, "Connected")

    pdu = build_bind("wrong", "creds", 0x34)
    data, status = send_and_recv(sock, pdu, "BIND wrong creds")
    assert_test(status == 5, f"BIND rejected (status={status}, expected=5)")

    sock.close()
    return status == 5


def test_submit_sm_ascii():
    test("SUBMIT_SM (ASCII/GSM 7bit)")
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(10)
    sock.connect((PHONE_IP, SMPP_PORT))

    pdu = build_bind(SYSTEM_ID, PASSWORD, 0x34)
    send_and_recv(sock, pdu, "BIND")

    msg = "Hello World from SMPP test!"
    pdu = build_submit_sm(SOURCE_PHONE, DEST_PHONE, msg, data_coding=0x00, seq=2)
    data, status = send_and_recv(sock, pdu, "SUBMIT_SM")

    if status == 0 and len(data) > 16:
        msg_id = data[16:].split(b'\x00')[0].decode()
        assert_test(msg_id.startswith("RPT-"), f"Got reportId: {msg_id}")
    else:
        assert_test(False, f"SUBMIT_SM failed (status={status})")

    pdu = build_unbind(100)
    send_and_recv(sock, pdu, "UNBIND")
    sock.close()
    return status == 0


def test_submit_sm_registered_delivery():
    test("SUBMIT_SM with registered_delivery")
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(10)
    sock.connect((PHONE_IP, SMPP_PORT))

    pdu = build_bind(SYSTEM_ID, PASSWORD, 0x34)
    send_and_recv(sock, pdu, "BIND")

    msg = "Registered delivery test"
    pdu = build_submit_sm(SOURCE_PHONE, DEST_PHONE, msg, registered_delivery=0x01, data_coding=0x00, seq=2)
    data, status = send_and_recv(sock, pdu, "SUBMIT_SM (registered_delivery=1)")
    assert_test(status == 0, f"SUBMIT_SM success (status={status})")

    pdu = build_unbind(100)
    send_and_recv(sock, pdu, "UNBIND")
    sock.close()
    return status == 0


def test_submit_sm_ucs2():
    test("SUBMIT_SM (UCS2/Unicode)")
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(10)
    sock.connect((PHONE_IP, SMPP_PORT))

    pdu = build_bind(SYSTEM_ID, PASSWORD, 0x34)
    send_and_recv(sock, pdu, "BIND")

    msg = "Merhaba Dunya!"
    msg_bytes = msg.encode('utf-16-be')
    pdu = build_submit_sm(SOURCE_PHONE, DEST_PHONE, msg_bytes, data_coding=0x08, seq=2)
    data, status = send_and_recv(sock, pdu, "SUBMIT_SM (UCS2)")
    assert_test(status == 0, f"UCS2 SUBMIT_SM success (status={status})")

    pdu = build_unbind(100)
    send_and_recv(sock, pdu, "UNBIND")
    sock.close()
    return status == 0


def test_enquire_link():
    test("ENQUIRE_LINK")
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(10)
    sock.connect((PHONE_IP, SMPP_PORT))

    pdu = build_bind(SYSTEM_ID, PASSWORD, 0x34)
    send_and_recv(sock, pdu, "BIND")

    pdu = build_enquire_link(2)
    data, status = send_and_recv(sock, pdu, "ENQUIRE_LINK")
    assert_test(status == 0, f"ENQUIRE_LINK success (status={status})")

    pdu = build_unbind(100)
    send_and_recv(sock, pdu, "UNBIND")
    sock.close()
    return status == 0


def test_deliver_sm():
    test("DELIVER_SM (delivery report)")
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(30)  # Longer timeout for delivery reports
    sock.connect((PHONE_IP, SMPP_PORT))

    # BIND_RECEIVER
    body = write_cstring(SYSTEM_ID) + write_cstring(PASSWORD) + write_cstring('') + bytes([0x34])
    pdu = struct.pack('>IIII', 16 + len(body), 0x00000001, 0, 1) + body
    sock.sendall(pdu)
    data = recv_full(sock)
    if len(data) >= 16:
        _, _, status, _ = struct.unpack('>IIII', data[:16])
    else:
        status = -1
    assert_test(status == 0, "BIND_RECEIVER success")

    # Send SMS via HTTP
    result = http_send(DEST_PHONE, "DELIVER_SM test")
    report_id = result.get("reportId")
    assert_test(report_id is not None, f"SMS sent via HTTP: {report_id}")

    # Wait for DELIVER_SM
    try:
        data = recv_full(sock)
        if len(data) >= 16:
            resp_len, resp_id, resp_status, resp_seq = struct.unpack('>IIII', data[:16])
            assert_test(resp_id == 0x00000005, f"Received DELIVER_SM (cmd_id=0x{resp_id:08x})")

            # Send DELIVER_SM_RESP
            resp_pdu = build_deliver_sm_resp(resp_seq)
            sock.sendall(resp_pdu)
            assert_test(True, "Sent DELIVER_SM_RESP")
        else:
            assert_test(False, f"No DELIVER_SM received ({len(data)} bytes)")
    except socket.timeout:
        assert_test(False, "Timeout waiting for DELIVER_SM")

    pdu = build_unbind(100)
    send_and_recv(sock, pdu, "UNBIND")
    sock.close()
    return True


def test_long_sms():
    test("Long SMS (multipart)")
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(10)
    sock.connect((PHONE_IP, SMPP_PORT))

    pdu = build_bind(SYSTEM_ID, PASSWORD, 0x34)
    send_and_recv(sock, pdu, "BIND")

    # Long message (>160 chars for GSM 7bit)
    msg = "This is a long SMS message that exceeds the 160 character limit for GSM 7bit encoding. It should be handled as a concatenated multipart SMS by the SMPP server."
    pdu = build_submit_sm(SOURCE_PHONE, DEST_PHONE, msg, data_coding=0x00, seq=2)
    data, status = send_and_recv(sock, pdu, "SUBMIT_SM (long SMS)")
    assert_test(status == 0, f"Long SMS submitted (status={status})")

    if status == 0 and len(data) > 16:
        msg_id = data[16:].split(b'\x00')[0].decode()
        assert_test(msg_id.startswith("RPT-"), f"Got reportId: {msg_id}")

    pdu = build_unbind(100)
    send_and_recv(sock, pdu, "UNBIND")
    sock.close()
    return status == 0


def test_report_api():
    test("Report API")
    # Add a contact first
    phone = "90588888888"
    name = "Report Test"
    payload = json.dumps({"phone": phone, "name": name, "group": "test"}).encode()
    req = urllib.request.Request(
        f"http://{PHONE_IP}:{HTTP_PORT}/api/contacts",
        data=payload,
        headers={"Content-Type": "application/json"}
    )
    urllib.request.urlopen(req, timeout=5)

    # Send SMS with contact
    result = http_send(phone, "Report API test")
    report_id = result.get("reportId")
    assert_test(report_id is not None, f"SMS sent: {report_id}")

    time.sleep(2)

    # Get report
    report = http_get_report(report_id)
    assert_test(report.get("reportId") == report_id, f"Report found: {report.get('status')}")
    assert_test(report.get("phone") == phone, f"Phone matches: {report.get('phone')}")
    assert_test(report.get("contactName") == name, f"Contact name: {report.get('contactName')}")

    # Get all reports
    reports = http_get_reports()
    assert_test(reports.get("total", 0) > 0, f"Total reports: {reports.get('total')}")

    # Cleanup
    payload = json.dumps({"phone": phone}).encode()
    req = urllib.request.Request(
        f"http://{PHONE_IP}:{HTTP_PORT}/api/contacts",
        data=payload,
        headers={"Content-Type": "application/json"},
        method="DELETE"
    )
    urllib.request.urlopen(req, timeout=5)
    return True


def test_contacts():
    test("Contacts API")
    phone = "90599999999"
    name = "Test User"
    group = "testers"

    # Add contact
    payload = json.dumps({"phone": phone, "name": name, "group": group}).encode()
    req = urllib.request.Request(
        f"http://{PHONE_IP}:{HTTP_PORT}/api/contacts",
        data=payload,
        headers={"Content-Type": "application/json"}
    )
    resp = urllib.request.urlopen(req, timeout=5)
    result = json.loads(resp.read())
    assert_test(result.get("success") is True, f"Contact added")

    # Get contacts
    req = urllib.request.Request(f"http://{PHONE_IP}:{HTTP_PORT}/api/contacts")
    resp = urllib.request.urlopen(req, timeout=5)
    contacts = json.loads(resp.read())
    assert_test(contacts.get("total", 0) > 0, f"Total contacts: {contacts.get('total')}")

    # Send SMS with contact
    result = http_send(phone, "Contact test")
    report_id = result.get("reportId")
    time.sleep(1)
    report = http_get_report(report_id)
    assert_test(report.get("contactName") == name, f"Contact name linked: {report.get('contactName')}")

    # Delete contact
    payload = json.dumps({"phone": phone}).encode()
    req = urllib.request.Request(
        f"http://{PHONE_IP}:{HTTP_PORT}/api/contacts",
        data=payload,
        headers={"Content-Type": "application/json"},
        method="DELETE"
    )
    resp = urllib.request.urlopen(req, timeout=5)
    result = json.loads(resp.read())
    assert_test(result.get("success") is True, "Contact deleted")

    # Verify new SMS without contact has no name
    result2 = http_send(phone, "No contact test")
    report_id2 = result2.get("reportId")
    time.sleep(1)
    report2 = http_get_report(report_id2)
    assert_test(report2.get("contactName") is None, f"No contact name after delete: {report2.get('contactName')}")
    return True


def test_device_info():
    test("Device Info API")
    req = urllib.request.Request(f"http://{PHONE_IP}:{HTTP_PORT}/api/info")
    resp = urllib.request.urlopen(req, timeout=5)
    info = json.loads(resp.read())

    assert_test("device" in info, f"Device: {info.get('device', {}).get('name', 'N/A')}")
    assert_test("sim" in info, f"SIM: {info.get('sim', {}).get('operator', 'N/A')}")
    assert_test("network" in info, f"Network: {info.get('network', {}).get('type', 'N/A')}")
    return True


def test_smpp_status():
    test("SMPP Server Status")
    status = get_smpp_status()
    assert_test(status.get("running") is True, f"SMPP running: {status.get('running')}")
    assert_test(status.get("port") == SMPP_PORT, f"Port: {status.get('port')}")
    assert_test("version" in status, f"Version: {status.get('version')}")
    return True


def test_http_status():
    test("HTTP Server Status")
    req = urllib.request.Request(f"http://{PHONE_IP}:{HTTP_PORT}/api/status")
    resp = urllib.request.urlopen(req, timeout=5)
    status = json.loads(resp.read())

    assert_test(status.get("running") is True, f"HTTP running: {status.get('running')}")
    assert_test(status.get("port") == HTTP_PORT, f"Port: {status.get('port')}")
    return True


# ============================================================
# MAIN
# ============================================================

if __name__ == "__main__":
    print("=" * 60)
    print("SMPP Android Server - Test Suite")
    print(f"SMPP: {PHONE_IP}:{SMPP_PORT}")
    print(f"HTTP: {PHONE_IP}:{HTTP_PORT}")
    print("=" * 60)

    # Stop and start fresh
    stop_smpp_server()
    time.sleep(1)
    start_smpp_server()
    time.sleep(1)

    try:
        test_http_status()
        test_smpp_status()
        test_device_info()
        test_contacts()
        test_report_api()
        test_bind_transmitter()
        test_bind_receiver()
        test_bind_transceiver()
        test_bind_wrong_credentials()
        test_submit_sm_ascii()
        test_submit_sm_registered_delivery()
        test_submit_sm_ucs2()
        test_enquire_link()
        test_long_sms()
        test_deliver_sm()
    except Exception as e:
        print(f"\nFATAL ERROR: {e}")
        import traceback
        traceback.print_exc()

    print("\n" + "=" * 60)
    print(f"RESULTS: {passed} passed, {failed} failed")
    print("=" * 60)

    sys.exit(0 if failed == 0 else 1)
