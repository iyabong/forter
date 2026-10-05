"""
ELM327 에뮬레이터

OBD 응답: Wikipedia "OBD-II PIDs" 표(Service 01)의 공식을 거꾸로 적용
  https://en.wikipedia.org/wiki/OBD-II_PIDs
  [표 공식] 데이터 바이트를 A, B, C, D로 표현
           ex: RPM = (256A + B) / 4

실행: python -u main.py           # COM4로 폰 연결 대기
확인: python -u main.py --pids    # 연결 없이 응답 표만 출력
"""

import sys
import serial

PORT = "COM4"

# 현재 차량 상태 - 사람이 읽는 단위(10진수)
state = {
    "load":         20.0,     # %
    "coolant":      85,       # 온도(섭씨)
    "rpm":          696,      # RPM
    "speed":        0,        # km/h
    "maf":          2.5,      # g/s
    "throttle":     15.0,     # %
    "voltage":      12.6,     # V (ATRV)
}

#   ---------- 값 → 데이터 바이트 ---------

def one_byte(x):
    """A 하나 (0-255)"""
    return [max(0, min(255, round(x)))]

def two_bytes(x):
    """A, B 두 개 (0~65535). A = 상위 바이트, B = 하위 바이트   ※ x = 256A + B"""
    v = max(0, min(65535, round(x)))
    return [v // 256, v % 256]

# PID: (이름, state 키, 인코더)
# 인코더 = Wiki 공식 역함수 ※ 주석: Wiki 표 그대로
PIDS = {
    0x04: ("Engine load",           "load",         lambda v: one_byte(v * 255 / 100)),     # 100/255 * A
    0x05: ("Coolant temp",          "coolant",      lambda v: one_byte(v + 40)),            # A - 40
    0x0C: ("Engine RPM",            "rpm",          lambda v: two_bytes(v * 4)),             # (256A + B) / 4
    0x0D: ("Vehicle speed",         "speed",        lambda v: one_byte(v)),                 # A
    0x10: ("MAF air flow rate",     "maf",          lambda v: two_bytes(v * 100)),          # (256A + B) / 100
    0x11: ("Throttle position",     "throttle",      lambda v: one_byte(v * 255 / 100)),     # 100/255 * A
}

def supported_bitmap():
    """
    PID 00: 지원 PID 목록, 4바이트(A, B, C, D) = 32비트.
    Wiki 표 기준 A의 최상위 비트 = PID 01, D의 최하위 비트 = PID 20.
    PIDS에 등록된 것만 1로 켜므로, PID를 추가하면 0100 응답도 자동으로 맞춰진다.
    """
    bits = 0
    for pid in PIDS:
        if 0x01 <= pid <= 0x20:
            bits |= 1 << (0x20 - pid)
    return [(bits >> shift) & 0xFF for shift in (24, 16, 8, 0)]

def bitmap_to_pids(data):
    """비트맵 4바이트 → 지원 pid 목록 (0100 응답 검산용) """
    bits = int.from_bytes(bytes(data), "big")   #   [A, B, C, D] → 32비트 정수 중 하나
    return [pid for pid in range(0x01, 0x21) if  bits & (1 << (0x20 - pid))]


# ---------- 응답 생성 ----------
def obd_response(service, pid, data):
    """응답 = [서비스 + 0x40, PID, 데이터...] 를 16진수 문자열로 (ATS0: 공백 없음)"""
    return bytes([service + 0x40, pid, *data]).hex().upper()


def handle_obd(cmd):
    """'010C' → 서비스 01, PID 0C"""
    if len(cmd) != 4:
        return  "?"
    try:
        service = int(cmd[:2], 16)
        pid = int(cmd[2:], 16)
    except ValueError:
        return "?"

    if service != 0x01:
        return "NO DATA"
    if pid == 0x00:
        return obd_response(service, pid, supported_bitmap())
    if pid in PIDS:
        _, key, encode = PIDS[pid]
        return obd_response(service, pid, encode(state[key]))
    return "NO DATA"        # 진짜 ELM327도 차가 모르는 PID면 NO DATA

def handle_at(cmd):
    if cmd in ("ATZ", "ATI"):
        return "ELM v2.3"
    if cmd == "ATRV":
        return f"{state['voltage']:.1f}V"
    return "OK"             # ATE0, ATL0, ATS0, ATH0, ATSP0 등

def reply_for(cmd):
    cmd = cmd.replace(" ", "").upper()
    if cmd.startswith("AT"):
        return handle_at(cmd)
    return handle_obd(cmd)

# ---------- 실행 ----------
def print_pids():
    # ---------- 표 1: 지원 PID (0100, 비트맵) ----------
    resp = reply_for("0100")
    bitmap = supported_bitmap()
    supported = bitmap_to_pids(bitmap)
    print(f"[0100] supported PIDs → {resp}")
    print(f"{'BYTE':<5} {'HEX':<4} {'BIN':<9} {'RANGE':<6} PIDS")
    print("-" * 40)
    for i, b in enumerate(bitmap):
        first = i * 8 + 1                       # 이 바이트가 담당하는 첫 PID (A=01, B=09, C=11, D=19)
        in_byte = [p for p in supported if first <= p < first + 8]
        pids = " ".join(f"{p:02X}" for p in in_byte) or "-"
        print(f"{'ABCD'[i]:<5} {b:02X}   {b:08b}  {first:02X}-{first + 7:02X}  {pids}")
    print()

    # ---------- 표 2: PID 값 ----------
    print(f"{'CMD(HEX)':<9} {'RESPONSE(HEX)':<14} {'DATA(DEC)':<17} MEANING")
    print("-" * 60)
    for pid, (name, key, encode) in PIDS.items():
        cmd = f"01{pid:02X}"
        print(f"{cmd:<9} {reply_for(cmd):<14} {str(encode(state[key])):<17} {name} = {state[key]}")

def serve():
    port = serial.Serial(PORT, timeout=1)   # 1초마다 read가 빠져나와서 Ctrl + C가 먹힘
    print(f"{PORT} 열림, 폰 연결 대기 중...")

    buf = b""
    while True:
        data = port.read(1)                         # 1바이트 읽기, 없으면 1초 후 b"" 반환
        if not data:
            continue
        if data == b"\r":                           # ELM327 명령은 \r로 끝
            cmd = buf.decode(errors="replace").strip()
            buf = b""
            if not cmd:
                continue
            resp = reply_for(cmd)
            port.write(f"{resp}\r\r>".encode())     # 응답 끝에 > 프롬프트
            print(f"받음: {cmd:<6} → 보냄: {resp}")
        else:
            buf += data

if __name__ == "__main__":
    if "--pids" in sys.argv:
        print_pids()
    else:
        serve()