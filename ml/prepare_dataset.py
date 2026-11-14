"""
PillCounter AI - YOLO 학습 데이터셋 준비 스크립트

입력 소스 (여러 개 혼합 가능):
  --app     앱 [이력 > 학습 데이터 내보내기] 로 받은 zip (또는 압축 해제 폴더). 여러 개 지정 가능
  --aihub   AI Hub '경구약제 이미지 데이터' 폴더 (COCO 형식 json + 이미지). 하위 폴더 전체 탐색
  --yolo    이미 YOLO 형식(images/, labels/)인 폴더 (예: Roboflow 내보내기)

출력:
  <out>/images/{train,val}/*.jpg
  <out>/labels/{train,val}/*.txt   (클래스 0 = pill)
  <out>/data.yaml

예)
  python prepare_dataset.py --app pill_dataset_20261114.zip --aihub D:/aihub/경구약제 --out datasets/pills
"""
from __future__ import annotations

import argparse
import json
import os
import random
import shutil
import sys
import tempfile
import zipfile
from collections import defaultdict
from pathlib import Path

IMG_EXT = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}


# ───────────────────────── 소스별 수집 ─────────────────────────
# 각 수집기는 (이미지 경로, [ "0 cx cy w h", ... ], 그룹키) 를 yield 한다.
# 그룹키: 같은 원본에서 나온 이미지가 train/val 에 섞이지 않도록 분할 단위로 사용.

def iter_yolo_dir(root: Path, tag: str):
    img_dir = root / "images"
    lbl_dir = root / "labels"
    if not img_dir.is_dir():
        print(f"  [경고] {root} 에 images/ 폴더가 없습니다", file=sys.stderr)
        return
    for img in sorted(img_dir.rglob("*")):
        if img.suffix.lower() not in IMG_EXT:
            continue
        rel = img.relative_to(img_dir).with_suffix(".txt")
        lbl = lbl_dir / rel
        if not lbl.exists():
            continue
        lines = []
        for line in lbl.read_text(encoding="utf-8").splitlines():
            parts = line.split()
            if len(parts) < 5:
                continue
            # 클래스는 단일 'pill' 로 통일
            box = clamp_box(*map(float, parts[1:5]))
            if box:
                lines.append(fmt(box))
        yield img, lines, f"{tag}:{img.stem}"


def iter_app_export(src: Path, workdir: Path):
    if src.is_file() and src.suffix.lower() == ".zip":
        dest = workdir / src.stem
        with zipfile.ZipFile(src) as z:
            z.extractall(dest)
        root = dest
    else:
        root = src
    yield from iter_yolo_dir(root, f"app:{src.stem}")


def iter_aihub(root: Path):
    """COCO 형식 json(이미지당/알약당 1개씩 분리된 경우 포함)을 이미지 단위로 병합한다."""
    images = {}
    for p in root.rglob("*"):
        if p.suffix.lower() in IMG_EXT:
            images.setdefault(p.name, p)
    boxes: dict[str, set] = defaultdict(set)
    sizes: dict[str, tuple[int, int]] = {}
    n_json = 0
    for jp in root.rglob("*.json"):
        try:
            data = json.loads(jp.read_text(encoding="utf-8"))
        except Exception:
            continue
        if not isinstance(data, dict) or "annotations" not in data:
            continue
        n_json += 1
        id2img = {}
        for im in data.get("images", []):
            name = Path(im.get("file_name", "")).name
            if not name:
                continue
            id2img[im.get("id")] = name
            if im.get("width") and im.get("height"):
                sizes[name] = (int(im["width"]), int(im["height"]))
        default_name = next(iter(id2img.values()), None)
        for ann in data.get("annotations", []):
            bbox = ann.get("bbox")
            if not bbox or len(bbox) != 4:
                continue
            name = id2img.get(ann.get("image_id"), default_name)
            if name:
                boxes[name].add(tuple(round(float(v), 1) for v in bbox))
    print(f"  AI Hub: json {n_json}개, 라벨 있는 이미지 {len(boxes)}장")
    for name, bset in sorted(boxes.items()):
        img = images.get(name)
        if img is None:
            continue
        w, h = sizes.get(name) or image_size(img)
        lines = []
        for x, y, bw, bh in bset:
            box = clamp_box((x + bw / 2) / w, (y + bh / 2) / h, bw / w, bh / h)
            if box:
                lines.append(fmt(box))
        if lines:
            yield img, lines, f"aihub:{name}"


# ───────────────────────── 유틸 ─────────────────────────

def image_size(path: Path) -> tuple[int, int]:
    from PIL import Image  # ultralytics 설치 시 함께 설치됨

    with Image.open(path) as im:
        return im.size


def clamp_box(cx, cy, w, h):
    x1, y1 = max(0.0, cx - w / 2), max(0.0, cy - h / 2)
    x2, y2 = min(1.0, cx + w / 2), min(1.0, cy + h / 2)
    if x2 - x1 < 1e-3 or y2 - y1 < 1e-3:
        return None
    return ((x1 + x2) / 2, (y1 + y2) / 2, x2 - x1, y2 - y1)


def fmt(box) -> str:
    return "0 {:.6f} {:.6f} {:.6f} {:.6f}".format(*box)


# ───────────────────────── main ─────────────────────────

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--app", nargs="*", default=[], help="앱에서 내보낸 zip/폴더")
    ap.add_argument("--aihub", nargs="*", default=[], help="AI Hub 경구약제 데이터 폴더")
    ap.add_argument("--yolo", nargs="*", default=[], help="YOLO 형식 폴더")
    ap.add_argument("--out", default="datasets/pills")
    ap.add_argument("--val", type=float, default=0.15, help="검증 비율")
    ap.add_argument("--max-aihub", type=int, default=0, help="AI Hub 이미지 최대 사용 수 (0=전체)")
    ap.add_argument("--app-repeat", type=int, default=1,
                    help="앱 실환경 데이터를 학습셋에 N배 복제 (AI Hub 대비 비중↑)")
    ap.add_argument("--seed", type=int, default=0)
    args = ap.parse_args()

    if not (args.app or args.aihub or args.yolo):
        ap.error("--app / --aihub / --yolo 중 하나 이상 지정하세요")

    out = Path(args.out)
    if out.exists():
        shutil.rmtree(out)
    for split in ("train", "val"):
        (out / "images" / split).mkdir(parents=True)
        (out / "labels" / split).mkdir(parents=True)

    rnd = random.Random(args.seed)
    samples = []  # (img, lines, group, source)
    with tempfile.TemporaryDirectory() as tmp:
        tmpdir = Path(tmp)
        for src in args.app:
            print(f"[앱] {src}")
            samples += [(i, l, g, "app") for i, l, g in iter_app_export(Path(src), tmpdir)]
        for src in args.yolo:
            print(f"[YOLO] {src}")
            samples += [(i, l, g, "yolo") for i, l, g in iter_yolo_dir(Path(src), f"yolo:{Path(src).name}")]
        for src in args.aihub:
            print(f"[AI Hub] {src}")
            ai = list(iter_aihub(Path(src)))
            rnd.shuffle(ai)
            if args.max_aihub:
                ai = ai[: args.max_aihub]
            samples += [(i, l, g, "aihub") for i, l, g in ai]

        if not samples:
            sys.exit("이미지를 하나도 찾지 못했습니다. 경로를 확인하세요.")

        groups = sorted({s[2] for s in samples})
        rnd.shuffle(groups)
        n_val = max(1, int(len(groups) * args.val)) if len(groups) > 1 else 0
        val_groups = set(groups[:n_val])

        stats = defaultdict(lambda: [0, 0])
        for idx, (img, lines, group, source) in enumerate(samples):
            split = "val" if group in val_groups else "train"
            copies = args.app_repeat if (source == "app" and split == "train") else 1
            for c in range(copies):
                name = f"{source}_{idx:06d}" + (f"_r{c}" if c else "")
                shutil.copy2(img, out / "images" / split / f"{name}{img.suffix.lower()}")
                (out / "labels" / split / f"{name}.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
                stats[split][0] += 1
                stats[split][1] += len(lines)

    yaml = out / "data.yaml"
    yaml.write_text(
        f"path: {out.resolve().as_posix()}\n"
        "train: images/train\n"
        "val: images/val\n"
        "names:\n"
        "  0: pill\n",
        encoding="utf-8",
    )
    print("\n완료:")
    for split in ("train", "val"):
        print(f"  {split}: 이미지 {stats[split][0]}장, 알약 박스 {stats[split][1]}개")
    print(f"  data.yaml: {yaml.resolve()}")
    if stats["val"][0] == 0:
        print("  [주의] 검증 이미지가 없습니다. 데이터를 더 모으세요.")


if __name__ == "__main__":
    main()
