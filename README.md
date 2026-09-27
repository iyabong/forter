# Forter

2012 KIA Forte LPi Hybrid의 OBD2 데이터를 수집하는 개인용 Android 앱입니다.
ELM327 호환 어댑터와 Bluetooth SPP로 연결해 주행 데이터를 기록하고, 분석은 별도 웹 앱에서 합니다.

- 패키지: `com.iyabong.forter`
- 언어/UI: Kotlin, Jetpack Compose
- Min SDK: 31 (Android 12)
- 어댑터: Vgate iCar Pro 2s (`Android-Vlink`, classic SPP)

## 구조

```
com.iyabong.forter
├── bluetooth/   # 페어링 기기 목록, SPP 연결
├── obd/         # ELM327 명령, PID 응답 해석
├── trip/        # 주행 단위 기록
├── data/        # 저장
└── ui/          # Compose 화면
```

## 빌드

```bash
./gradlew installDebug
```

버전은 `versionName`에 숫자를 직접 올리고 git 해시를 붙입니다 (`0.1.4-<hash>`).
`versionCode`는 커밋 수입니다.

## OBD / ELM327 명령

### 구조
- `AT...` : 어댑터(ELM327)에게 하는 명령. 대소문자·공백 무시, 끝에 `\r`
- `01XX` : 차 ECU로 전달되는 OBD 요청. `01` = 현재 데이터, `XX` = PID
- 응답은 `>` 프롬프트가 나오면 끝

### 초기화 (연결 직후 1회)
| 명령 | 의미 | 정상 응답 |
|------|------|-----------|
| ATZ | 리셋 | `ELM327 v2.3` |
| ATE0 | 에코 끄기 | OK |
| ATL0 | 줄바꿈 끄기 | OK |
| ATS0 | 공백 끄기 | OK |
| ATH0 | 헤더 끄기 | OK |
| ATSP0 | 프로토콜 자동 탐색 | OK |

### PID
| 요청 | 항목 | 응답 예 | 계산 | 단위 |
|------|------|---------|------|------|
| 0100 | 지원 PID 목록(01~20) | `4100XXXXXXXX` | 비트맵 | - |
| 010C | 엔진 RPM | `410C1AF8` | (A*256+B)/4 | rpm |
| 010D | 차속 | `410D32` | A | km/h |

### 실패 응답
- `NO DATA` : ECU가 응답하지 않음 (시동 꺼짐, 미지원 PID 등)
- `?` : 어댑터가 모르는 명령
- `SEARCHING...` : `ATSP0` 후 첫 요청에서 프로토콜을 찾는 중

### 참고 문서
- ELM327 데이터시트 (AT 명령 전체, 응답 형식) — 목차의 **AT Command Summary**, **AT Command Descriptions**만 보면 됨
    - [SparkFun 사본](https://cdn.sparkfun.com/assets/learn_tutorials/8/3/ELM327DS.pdf)
    - [ELMduino 저장소 사본](https://github.com/PowerBroker2/ELMduino/blob/master/reference/ELM327DS.pdf)
    - 제조사 ELM Electronics는 2022년 폐업, 공식 배포처 없음
- PID 정의: [Wikipedia – OBD-II PIDs](https://en.wikipedia.org/wiki/OBD-II_PIDs)