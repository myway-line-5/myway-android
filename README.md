# MyWay (마이웨이) - Android

자연어 조건 해석 기반 맞춤형 지하철 경로 탐색 앱의 **안드로이드 앱** 저장소입니다.
서울 지하철 2·5·9호선을 대상으로, "40분 이내, 환승은 한 번 이하로, 가장 저렴하게"처럼 문장으로 조건을 입력하면 경로를 찾아 줍니다.

> 명지대학교 컴퓨터공학과 팀프로젝트1 · 팀 5호선

## 주요 기능

**Phase 1 (필수)**
- 일반 모드: 출발·도착역을 직접 선택해 경로 검색 (초성 검색 지원)
- AI 모드: 자연어 문장으로 조건 입력 → 해석 결과 확인·수정 → 경로 탐색
- 최단시간 · 최소환승 · 최소비용 3기준 경로 비교
- 최대 소요시간 / 환승 횟수 / 요금 제한과 선호 조건
- 즐겨찾기, 최근 검색, 설정 (로컬 저장)

**Phase 2 (최종 목표)**
- 음성 입력·안내 (STT/TTS)
- 서울시 실시간 도착정보 기반 하차·환승 안내
- 화면이 꺼진 상태에서도 알림 유지 (포그라운드 서비스)
- 다국어

## 시스템 구조

앱은 경로를 직접 계산하지 않고, 메인 서버 API만 호출합니다.

```
앱 (이 저장소)  →  메인 서버  →  AI 서버  →  Gemini API
                     └ 경로 탐색(다익스트라)
```

| 저장소 | 역할 |
|---|---|
| `myway-android` | 안드로이드 앱 (이 저장소) |
| `myway-main-server` | 메인 서버: 역 확정, 경로 탐색 요청 처리 |
| `myway-algorithm` | 전처리 데이터, 그래프, 다익스트라 |
| `myway-ai-server` | 자연어 → 조건 JSON 변환 (Gemini Function Calling) |

## 기술 스택

- Kotlin, Android Studio (XML View)
- MVVM (View - ViewModel - Repository)
- Retrofit, OkHttp, Gson
- Navigation, Lifecycle, Coroutines
- Room (예정)
- minSdk 28 (Android 9.0 이상)

## 프로젝트 구조

```
com.myway.app
├─ ui          # 화면 (home, search, aimode, result, favorites, settings, common)
├─ data
│  └─ repository   # Repository 인터페이스와 Fake 구현
├─ domain
│  └─ model        # Station, RouteCondition, RouteResult 등
└─ util
```

화면은 `Repository` 인터페이스만 알고 있어서, 서버가 준비되기 전에는 `Fake` 구현을 넣어 개발합니다. 서버가 준비되면 같은 인터페이스의 실제 구현으로 교체합니다.

## 실행 방법

1. Android Studio로 이 저장소를 엽니다.
2. Gradle Sync를 실행합니다.
3. 에뮬레이터 또는 실기기(Android 9.0 이상)에서 실행합니다.

현재는 서버 없이 Fake 데이터로 동작합니다.

### 서버와 연결해서 실행 (선택)

기본값은 서버 없이 Fake 데이터로 동작합니다. 로컬 메인 서버와 연결하려면:

1. `myway-main-server`를 실행합니다 (`python main.py`, 포트 8000).
2. 폰(또는 에뮬레이터)을 USB로 연결하고 `adb reverse tcp:8000 tcp:8000`을 실행합니다.
3. `RepositoryProvider.kt`에서 `USE_FAKE`를 `false`로 바꿉니다.

debug 빌드에서만 `127.0.0.1`·`10.0.2.2`로의 http 통신이 허용됩니다.

## 진행 상황

- [x] 프로젝트 생성, 패키지 구조
- [x] 도메인 모델 (Station, StationSlot, RouteCondition, RouteResult 등)
- [x] StationRepository + FakeStationRepository
- [x] RouteRepository + FakeRouteRepository
- [x] 서버 연동 기반 (DTO, Retrofit, 세션 헤더, ErrorMapper, RealStationRepository)
- [x] 검색 화면, 역 선택 시트 (출발·도착 지정)
- [x] Fake/Real 전환 스위치 (RepositoryProvider)
- [x] 역 검색 서버 연결 실기기 확인
- [x] Navigation 뼈대, 홈(노선도)·결과 화면
- [x] 즐겨찾기·최근 검색·최근 경로 (LocalStore, 로컬 저장)
- [x] 경로 탐색 서버 연동 (`/v1/routes/search`, RealRouteRepository, 실기기 확인)
- [x] 환승역에서 모든 호선 후보로 경로 탐색해 가장 빠른 경로 선택 (실기기 확인)
- [ ] 3기준(최단시간·최소환승·최소요금) 경로를 요청해서 탭 3개로 표시 (서버 확인 필요)
- [ ] 요금 표시 (서버가 현재 null 반환)
- [x] AI 모드 해석 서버 연동 (`/v1/conditions/interpret`, RealConditionRepository, mock으로 실기기 확인)
- [ ] AI 모드 역 후보 선택·확정 연동 (`/v1/stations/confirm`, Gemini 모드에서 확인 필요)
- [ ] 역 상세 정보 (서버 API 확인 필요)
- [ ] 안 쓰는 Room 코드 정리 (즐겨찾기·최근 검색은 LocalStore로 통일됨)
- [ ] Phase 2 기능