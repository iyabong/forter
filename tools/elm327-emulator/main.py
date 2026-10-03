import serial

PORT = "COM4"

port = serial.Serial(PORT, timeout=1)   # 1초마다 read가 빠져나와서 Ctrl +C가 먹힘
print(f"{PORT} 열림, 폰 연결 대기 중...")

buf = b""
while True:
    data = port.read(1)     # 1바이트 읽기, 없으면 1초 후 b"" 반환
    if not data:
        continue
    if data == b"\r":       # ELM327 명령은 \r로 끝
        print("받음:", buf.decode(errors="replace"))
        buf = b""
    else:
        buf += data