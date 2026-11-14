# YOLO 알약 탐지 모델 학습 가이드

```
[1] 데이터 모으기 ──▶ [2] 데이터셋 구성 ──▶ [3] 학습(Colab GPU) ──▶ [4] 앱에 넣기 ──▶ [1] 반복
   앱 촬영+보정          prepare_dataset.py      colab_train.ipynb       assets/*.tflite
```

## 1. 데이터 모으기 (가장 중요)

### A. 앱으로 직접 모으기 — 실환경 데이터 (권장)
1. 앱으로 트레이를 촬영 → 결과 화면에서 **틀린 마커를 터치로 보정** → **저장 및 완료**
   - 저장된 마커 위치가 그대로 정답 라벨이 됩니다. 보정을 꼼꼼히 할수록 모델이 좋아집니다.
2. **이력 → 학습 데이터 내보내기** → Drive/메일/Quick Share 로 PC 에 `pill_dataset_*.zip` 전송

권장 수집량 (첫 모델 기준):
| 항목 | 목표 |
|---|---|
| 이미지 수 | 최소 150장, 권장 300장 이상 |
| 트레이 색 | 흰색 / 파란색 / 검은색 등 실제 쓰는 트레이 전부 |
| 알약 종류 | 흰 원형정, 유색정, 장형정, 경질·연질 캡슐 골고루 |
| 배치 | 듬성듬성 / 빽빽하게 붙음 / 일부 겹침 |
| 조명 | 형광등, 자연광, 반사·그림자 있는 상태 |
| 수량 | 1장당 10~100정 다양하게 |

> 같은 알약 무더기를 섞어서 여러 번 찍어도 좋은 데이터입니다.
> 앱에서 수량이 맞을 때까지 보정한 뒤 저장해야 정답 라벨이 됩니다.

### B. AI Hub 공개 데이터 (보조)
- [AI Hub](https://aihub.or.kr) → "경구약제 이미지 데이터" 신청·다운로드
- 조합(여러 알약) 이미지 + COCO 형식 json 을 사용합니다. 압축을 풀어 한 폴더에 두세요.
- 배경·조명이 실제 약국과 달라서 **단독으로는 부족하며**, A 데이터와 섞어 쓸 때 효과가 큽니다.

## 2. 데이터셋 구성
```bash
cd ml
python prepare_dataset.py --app pill_dataset_*.zip --out datasets/pills
# AI Hub 혼합 시 (실환경 데이터 비중을 3배로)
python prepare_dataset.py --app pill_dataset_*.zip --aihub D:/aihub/경구약제 --max-aihub 3000 --app-repeat 3 --out datasets/pills
```
이미 YOLO 형식(images/labels)인 데이터(Roboflow 등)는 `--yolo <폴더>` 로 추가할 수 있습니다.

## 3. 학습

### 방법 1: Google Colab (권장 — 이 PC 에는 NVIDIA GPU 가 없음)
1. https://colab.research.google.com → 파일 업로드 → `colab_train.ipynb`
2. 런타임 유형 **T4 GPU** 선택
3. 셀 순서대로 실행, 업로드 단계에서 `prepare_dataset.py`, `train.py`, 앱 zip 파일들 선택
4. 마지막 셀이 `pill_detector.tflite` 를 다운로드해 줍니다

### 방법 2: 로컬 PC
```bash
cd ml
python -m venv .venv && .venv\Scripts\activate
pip install -r requirements.txt
python train.py --data datasets/pills/data.yaml --device cpu --batch 8 --install
```
- Windows 에서는 자동으로 **ONNX** 형식(`pill_detector.onnx`)으로 변환됩니다 (TFLite/LiteRT 변환은 Linux·macOS 전용).
  앱은 TFLite 와 ONNX 를 모두 지원하며, 둘 다 있으면 TFLite 를 우선 사용합니다.
- CPU 학습 속도: 이미지 65장 × 15 epoch ≈ 11분 (Intel Core Ultra 5 225U 실측). 학습 중 RAM 을 많이 쓰므로 에뮬레이터는 끄고 돌리세요. 수백 장 × 120 epoch 는 수 시간 → Colab 권장.

### 결과 보기
- `mAP50` : 알약 위치 탐지 정확도 (0.95 이상 목표). **높아도 개수가 틀릴 수 있으니** 아래 지표를 보세요
- **[계수 정확도]** : 검증 이미지 중 개수를 정확히 맞춘 비율 — 앱의 실제 목표(99.5%)와 직결.
  앱과 동일한 중복 제거(NMS + 포함 박스 제거)로 conf 별 결과와 **권장 감도 슬라이더 값**을 출력합니다
- 결과가 부족하면 → 틀린 유형(붙은 알약, 흰 트레이 등)의 사진을 더 모아 1번부터 반복

## 4. 앱에 넣기
- 받은 `pill_detector.tflite` 또는 `pill_detector.onnx` → `app/src/main/assets/`
  (로컬 학습 시 `--install` 이 자동 복사)
- Android Studio 에서 다시 실행 → 결과 화면 상단에 **"YOLO AI 엔진 (TFLite/ONNX)"** 표시되면 적용 완료
- 결과 화면의 **감도** 슬라이더 = 탐지 신뢰도 기준 (50 → conf 0.40, 높일수록 더 많이 잡음)
- 모델 파일을 지우면 다시 영상처리 엔진으로 동작합니다

## 팁
- **모델 크기**: `yolo11n` (빠름, 기본) → 정확도가 부족하면 `--model yolo11s.pt`
- **빽빽한 알약**: `--imgsz 960` 으로 학습하면 작은 알약 탐지가 좋아지지만 앱 추론이 약 2배 느려집니다
- **선순환**: YOLO 모델 적용 후에도 계속 보정·저장·내보내기 → 재학습하면 점점 정확해집니다
