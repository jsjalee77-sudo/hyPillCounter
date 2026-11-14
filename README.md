# PillCounter AI (hyPillCounter)

카메라로 트레이 위 알약(정제·캡슐)을 촬영해 자동 계수하는 Android 앱 (Kotlin · Jetpack Compose · CameraX).

## 기능 (MVP + 2단계 일부)
- 실시간 계수 미리보기 + 가이드 박스, 고해상도 촬영 후 정밀 분석
- 알약별 번호 마커 오버레이, 총 개수 대형 표시
- 터치 보정: 알약 터치 = 제외(−1), 빈 곳 터치 = 추가(+1), 되돌리기/초기화, 핀치 확대
- 목표 수량 알림: 일치 시 녹색 플래시 + 짧은 진동, 부족/초과 시 경고 색상 + 3회 진동
- 감도 슬라이더로 재분석
- 색상·모양별 그룹 카운팅 (예: 흰색 원형 15정 / 노란색 장형 15정)
- 계수 이력 저장 (SQLite + 원본/마킹 이미지), 날짜별 목록, 상세, 삭제
- 갤러리 이미지 불러오기

## 탐지 엔진
| 엔진 | 사용 조건 |
|---|---|
| **YOLO AI 엔진** (LiteRT/TFLite) | `app/src/main/assets/pill_detector.tflite` 가 있으면 자동 사용 |
| **영상처리 엔진** (기본) | 모델이 없을 때. 배경 추정 → Otsu 이진화 → 연결요소 → 면적 기반 붙은 알약 분리(k-means) |

### YOLO 모델 학습
**[ml/README.md](ml/README.md)** 참고. 요약:
1. 앱에서 촬영·보정·저장 → 이력 → **학습 데이터 내보내기** (YOLO 라벨 zip)
2. `ml/prepare_dataset.py` 로 데이터셋 구성 (AI Hub 경구약제 데이터 혼합 가능)
3. `ml/colab_train.ipynb` (Colab GPU) 또는 `ml/train.py` 로 학습 → TFLite 변환
4. `pill_detector.tflite` → `app/src/main/assets/` 에 넣고 다시 빌드

## 촬영 팁
어두운 무광 트레이 + 균일한 조명에서 가장 정확합니다. 알약은 가급적 겹치지 않게 펼쳐 주세요.

## 빌드 / 테스트
```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest   # 탐지 엔진 합성 이미지 테스트
```

## 배포용(release) APK 만들기
1. 프로젝트 루트에 `keystore.properties` 작성 (Git 에 올리지 않음)
   ```
   storeFile=C:/Users/admin/keystores/jeongpill-release.jks
   storePassword=...
   keyAlias=jeongpill
   keyPassword=...
   ```
2. `gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`
   - 실제 폰(arm64) 전용, 코드 최적화(R8) 적용, 약 40MB
- **서명 키(.jks)와 비밀번호는 반드시 별도 백업하세요.** 잃어버리면 같은 앱으로 업데이트할 수 없습니다.
