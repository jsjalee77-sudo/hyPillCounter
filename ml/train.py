"""
PillCounter AI - YOLO 학습 + 모델 변환 + 앱 assets 설치

  python train.py --data datasets/pills/data.yaml                 # 학습 → 평가 → 변환
  python train.py --data datasets/pills/data.yaml --install       # + 앱 assets 에 복사
  python train.py --export-only runs/detect/pill/weights/best.pt --install

변환 형식 (--format):
  auto   Windows → onnx, Linux/macOS(Colab) → tflite   (기본)
  onnx   ONNX Runtime 으로 실행 (모든 OS 에서 변환 가능)
  tflite LiteRT/TFLite (Linux x86 / macOS 에서만 변환 가능)

GPU 가 없으면 CPU 로 학습되며 느립니다. 이 경우 colab_train.ipynb (Google Colab 무료 GPU) 를 권장합니다.
"""
from __future__ import annotations

import argparse
import platform
import shutil
from pathlib import Path

APP_ASSETS = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "assets"
MODEL_NAMES = {"tflite": "pill_detector.tflite", "onnx": "pill_detector.onnx"}

# 앱(YoloDetector.kt)과 동일한 중복 제거 기준
IOU_TH = 0.5
IOS_TH = 0.7


def dedup(xyxy, conf):
    """NMS(IoU) + 포함 관계(작은 박스 면적 대비 교집합) 제거. 앱과 같은 규칙으로 개수를 센다."""
    order = conf.argsort()[::-1]
    kept = []
    for i in order:
        a = xyxy[i]
        dup = False
        for j in kept:
            b = xyxy[j]
            ix = max(0.0, min(a[2], b[2]) - max(a[0], b[0]))
            iy = max(0.0, min(a[3], b[3]) - max(a[1], b[1]))
            inter = ix * iy
            if inter <= 0:
                continue
            aa = (a[2] - a[0]) * (a[3] - a[1])
            ab = (b[2] - b[0]) * (b[3] - b[1])
            if inter / (aa + ab - inter) > IOU_TH or inter / max(min(aa, ab), 1e-6) > IOS_TH:
                dup = True
                break
        if not dup:
            kept.append(i)
    return kept


def count_accuracy(model, data_yaml: str, imgsz: int, confs=(0.25, 0.35, 0.45, 0.55)):
    """검증셋에서 '이미지별 개수를 정확히 맞춘 비율' (계수 앱의 실제 KPI) 을 conf 별로 계산한다."""
    import yaml

    cfg = yaml.safe_load(Path(data_yaml).read_text(encoding="utf-8"))
    root = Path(cfg.get("path", Path(data_yaml).parent))
    val_dir = root / cfg["val"]
    imgs = [p for p in sorted(val_dir.iterdir()) if p.suffix.lower() in {".jpg", ".jpeg", ".png", ".bmp", ".webp"}]
    if not imgs:
        return
    truths = []
    preds = []
    for img in imgs:
        lbl = root / "labels" / img.parent.name / (img.stem + ".txt")
        truths.append(len([l for l in lbl.read_text().splitlines() if l.strip()]) if lbl.exists() else 0)
        r = model.predict(str(img), imgsz=imgsz, conf=min(confs), iou=IOU_TH, max_det=1000, verbose=False)[0]
        preds.append((r.boxes.xyxy.cpu().numpy(), r.boxes.conf.cpu().numpy()))
    print(f"\n[계수 정확도] 검증 이미지 {len(imgs)}장 (앱과 동일한 중복 제거 적용)")
    best = None
    for c in confs:
        exact = 0
        err = 0
        for t, (xyxy, conf) in zip(truths, preds):
            m = conf >= c
            n = len(dedup(xyxy[m], conf[m]))
            exact += n == t
            err += abs(n - t)
        acc = exact / len(imgs) * 100
        print(f"  conf {c:.2f}: 개수 일치 {exact}/{len(imgs)} ({acc:.1f}%), 평균 오차 {err / len(imgs):.2f}정")
        if best is None or acc > best[1]:
            best = (c, acc)
    sens = (0.6 - best[0]) / 0.4
    print(f"  → 최적 conf {best[0]:.2f} (앱 감도 슬라이더 약 {max(0, min(100, round(sens * 100)))})")


def resolve_format(fmt: str) -> str:
    if fmt != "auto":
        return fmt
    is_linux_x86 = platform.system() == "Linux" and platform.machine().lower() in {"x86_64", "amd64"}
    return "tflite" if (is_linux_x86 or platform.system() == "Darwin") else "onnx"


def export_model(weights: str, imgsz: int, fmt: str, install: bool):
    from ultralytics import YOLO

    fmt = resolve_format(fmt)
    model = YOLO(weights)
    try:
        if fmt == "onnx":
            out = Path(model.export(format="onnx", imgsz=imgsz, simplify=True, dynamic=False))
        else:
            out = Path(model.export(format="tflite", imgsz=imgsz))
            if out.is_dir():
                cands = sorted(out.rglob("*float32.tflite")) or sorted(out.rglob("*.tflite"))
                out = cands[0]
    except Exception as e:  # noqa: BLE001
        print(f"\n[변환 실패] {fmt}: {e}")
        if fmt == "tflite":
            print("  → Windows 에서는 --format onnx 를 쓰거나, Colab 에서 --export-only 로 변환하세요.")
        return None
    print(f"\n변환 완료 ({fmt}): {out}")
    if install:
        APP_ASSETS.mkdir(parents=True, exist_ok=True)
        dst = APP_ASSETS / MODEL_NAMES[fmt]
        shutil.copy2(out, dst)
        (APP_ASSETS / "pill_labels.txt").write_text("pill\n", encoding="utf-8")
        # 앱은 tflite 를 onnx 보다 우선 사용하므로, 다른 형식의 이전 모델은 제거해 혼동을 막는다
        for other, name in MODEL_NAMES.items():
            if other != fmt and (APP_ASSETS / name).exists():
                (APP_ASSETS / name).unlink()
                print(f"  이전 모델 제거: {name}")
        print(f"앱에 설치됨: {dst}\n→ Android Studio 에서 다시 빌드하면 'YOLO AI 엔진' 으로 동작합니다.")
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", help="data.yaml 경로")
    ap.add_argument("--model", default="yolo11n.pt", help="기본 모델 (n=가장 가벼움, s=더 정확)")
    ap.add_argument("--epochs", type=int, default=120)
    ap.add_argument("--imgsz", type=int, default=640)
    ap.add_argument("--batch", type=int, default=-1, help="-1 = GPU 메모리에 맞춰 자동")
    ap.add_argument("--device", default=None, help="0 (GPU) / cpu / mps")
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--name", default="pill")
    ap.add_argument("--format", default="auto", choices=["auto", "onnx", "tflite"])
    ap.add_argument("--install", action="store_true", help="변환된 모델을 앱 assets 에 복사")
    ap.add_argument("--export-only", metavar="WEIGHTS", help="학습 없이 best.pt 만 변환")
    args = ap.parse_args()

    if args.export_only:
        export_model(args.export_only, args.imgsz, args.format, args.install)
        return
    if not args.data:
        ap.error("--data 를 지정하세요")

    from ultralytics import YOLO

    model = YOLO(args.model)
    model.train(
        data=args.data,
        epochs=args.epochs,
        imgsz=args.imgsz,
        batch=args.batch,
        device=args.device,
        workers=args.workers,
        name=args.name,
        exist_ok=True,
        patience=30,
        # 알약은 회전·상하좌우 반전에 무관 → 증강 강화
        degrees=180,
        flipud=0.5,
        fliplr=0.5,
        # 트레이 색/조명 변화 대응
        hsv_h=0.03,
        hsv_s=0.6,
        hsv_v=0.5,
        mosaic=1.0,
        close_mosaic=min(15, max(1, args.epochs // 4)),
        max_det=1000,
    )
    best = Path(model.trainer.best)
    print(f"\n최고 가중치: {best}")
    best_model = YOLO(str(best))
    best_model.val(data=args.data, imgsz=args.imgsz, max_det=1000)
    count_accuracy(best_model, args.data, args.imgsz)
    export_model(str(best), args.imgsz, args.format, args.install)


if __name__ == "__main__":
    main()
