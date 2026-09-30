"""Reproducible public handwriting evaluation; desktop timings are not Android timings."""

from pathlib import Path
import argparse
import hashlib
import json
import platform
import time

import numpy as np
import onnxruntime as ort
from PIL import Image, ImageDraw, ImageFont
from handwriting_quality import candidates, legacy_candidates, load_samples, rasterize
from handwriting_stroke_model import PackedStrokeModel, fuse


ROOT = Path(__file__).resolve().parents[1]
GLYPHS = "\u4e2d\u4eba\u5de5\u5b66\u8f93\u5165\u6cd5\u5929\u5730\u4f60\u597d\u6d4b\u8bd5\u8bed\u8a00"


def image_for(character: str, font: ImageFont.FreeTypeFont) -> np.ndarray:
    source = Image.new("L", (128, 128), 255)
    ImageDraw.Draw(source).text((12, 12), character, font=font, fill=0)
    box = Image.eval(source, lambda value: 255 - value).getbbox()
    if box is None:
        raise ValueError(f"Font lacks glyph: {character}")
    cropped = source.crop(box)
    scale = 36 / max(cropped.size)
    resized = cropped.resize(tuple(max(1, round(value * scale)) for value in cropped.size), Image.Resampling.LANCZOS)
    padded = Image.new("L", (160, 48), 255)
    padded.paste(resized, (round(24 - resized.width / 2), round(24 - resized.height / 2)))
    plane = np.asarray(padded, dtype=np.float32) / 127.5 - 1
    return np.stack((plane, plane, plane))[None, ...]


def evaluate_samples(session: ort.InferenceSession, characters: list[str], samples: list[dict], stroke_models: list[PackedStrokeModel]) -> list[dict]:
    results = []
    for sample in samples:
        started = time.perf_counter()
        image = rasterize(sample["strokes"])
        inference_started = time.perf_counter()
        output = session.run(None, {"x": image})[0]
        inference_ms = (time.perf_counter() - inference_started) * 1000
        image_ranked = candidates(output, characters)
        stroke_results = [model.recognize(sample["strokes"]) for model in stroke_models]
        ranked = fuse(stroke_results, image_ranked)
        elapsed = (time.perf_counter() - started) * 1000
        baseline = legacy_candidates(session.run(None, {"x": rasterize(sample["strokes"], padding=1, width=160)})[0], characters)
        results.append({"id": sample["id"], "script": sample["script"], "label": sample["label"],
                        "in_vocabulary": sample["label"] in characters or any(sample["label"] in m.labels for m in stroke_models),
                        "candidates": ranked, "image_candidates": image_ranked,
                        "baseline_candidates": baseline, "inference_ms": inference_ms, "total_ms": elapsed})
    return results


def summary(results: list[dict]) -> dict:
    report = {}
    for script in ("simplified", "traditional"):
        subset = [r for r in results if r["script"] == script]
        if not subset:
            continue
        counts = {"count": len(subset), "in_vocabulary": sum(r["in_vocabulary"] for r in subset)}
        for name in ("candidates", "image_candidates", "baseline_candidates"):
            counts[name] = {"top1": sum(bool(r[name]) and r[name][0] == r["label"] for r in subset),
                            "top8": sum(r["label"] in r[name][:8] for r in subset),
                            "top16": sum(r["label"] in r[name] for r in subset),
                            "empty": sum(not r[name] for r in subset)}
        counts["inference_ms"] = {"median": float(np.median([r["inference_ms"] for r in subset])),
                                   "p95": float(np.percentile([r["inference_ms"] for r in subset], 95))}
        report[script] = counts
    return report


def evaluate_font(session: ort.InferenceSession, characters: list[str], font_path: Path) -> None:
    font = ImageFont.truetype(str(font_path), 96)
    top1 = top8 = 0
    for glyph in GLYPHS:
        result = legacy_candidates(session.run(None, {"x": image_for(glyph, font)})[0], characters)
        top1 += bool(result) and result[0] == glyph
        top8 += glyph in result
    print(f"Font-only legacy sanity check, NOT handwriting accuracy: top-1 {top1}/{len(GLYPHS)}, top-8 {top8}/{len(GLYPHS)}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--font", type=Path, help="Run the legacy font-only sanity check instead")
    parser.add_argument("--fixtures", type=Path, default=ROOT / "build/handwriting-fixtures")
    parser.add_argument("--split", choices=("development", "validation", "all"), default="validation")
    parser.add_argument("--count", type=int, default=256, help="Maximum samples per script, selected by stable SHA order")
    parser.add_argument("--output", type=Path, default=ROOT / "build/handwriting-evaluation/validation.json")
    parser.add_argument("--stroke-models", type=Path, default=ROOT / "build/handwriting-stroke-model")
    args = parser.parse_args()
    model = ROOT / "build/handwriting-model/inference.onnx"
    characters = [""] + (ROOT / "build/handwriting-model/characters.txt").read_text(encoding="utf-8").splitlines() + [" "]
    if args.count not in range(1, 10_001):
        parser.error("--count must be between 1 and 10000")
    options = ort.SessionOptions()
    options.log_severity_level = 4
    options.intra_op_num_threads = options.inter_op_num_threads = 1
    session = ort.InferenceSession(str(model), sess_options=options, providers=["CPUExecutionProvider"])
    if args.font:
        evaluate_font(session, characters, args.font)
        return
    samples = load_samples(args.fixtures, args.split, args.count)
    if not samples:
        raise ValueError("No validated public stroke samples")
    stroke_models = [PackedStrokeModel(args.stroke_models / f"stroke-{name}.zsh") for name in ("simplified", "traditional")]
    results = evaluate_samples(session, characters, samples, stroke_models)
    independent_samples = [sample for sample in load_samples(args.fixtures, "all", 10_000) if "/test/" in sample["id"]]
    independent_results = evaluate_samples(session, characters, independent_samples, stroke_models)
    report = {"split": args.split, "selection_count_per_script": args.count,
              "model_sha256": hashlib.sha256(model.read_bytes()).hexdigest(),
              "fixture_manifest_sha256": hashlib.sha256((args.fixtures / "sources.json").read_bytes()).hexdigest(),
              "runtime": {"platform": platform.platform(), "onnxruntime": ort.__version__, "threads": 1},
              "scope": "Simplified samples overlap stroke-model training data: regression only. Independent traditional test samples are reported separately; no writer-held-out population claim.",
              "stroke_model_sha256": {name: hashlib.sha256((args.stroke_models / f"stroke-{name}.zsh").read_bytes()).hexdigest()
                                      for name in ("simplified", "traditional")},
              "raster": {"width": 96, "height": 48, "extent": 36, "stroke_width": 2.5, "right_padding": 0},
              "summary": summary(results), "samples": results,
              "independent_traditional_summary": summary(independent_results), "independent_traditional_samples": independent_results}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report["summary"], indent=2))
    print("Independent traditional test corpus:")
    print(json.dumps(report["independent_traditional_summary"], indent=2))
    print(f"Saved evaluation report: {args.output}")


if __name__ == "__main__":
    main()
