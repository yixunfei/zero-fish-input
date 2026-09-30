"""Offline, public-stroke quality helpers; never reads device or editor data."""

from pathlib import Path
import hashlib
import json
import unicodedata
import xml.etree.ElementTree as ET

import numpy as np
from PIL import Image, ImageDraw


def is_han(value: str) -> bool:
    return len(value) == 1 and ("CJK UNIFIED IDEOGRAPH" in unicodedata.name(value, "")
                               or "CJK COMPATIBILITY IDEOGRAPH" in unicodedata.name(value, "")
                               or value == "\u3007")


def load_samples(directory: Path, split: str, count: int) -> list[dict]:
    """Use a fixed character partition, independent of recognition/model results."""
    manifest = json.loads((directory / "sources.json").read_text(encoding="utf-8"))
    result = []
    for source in manifest:
        path = directory / source["path"]
        data = path.read_bytes()
        if len(data) != source["bytes"] or hashlib.sha256(data).hexdigest() != source["sha256"]:
            raise ValueError("Fixture checksum mismatch")
        if path.suffix != ".xml":
            continue
        root = ET.fromstring(data)
        elements = [root] if root.tag == "character" else root.findall(".//character")
        script = "simplified" if "simplified-chinese" in source["path"] else "traditional"
        for index, element in enumerate(elements):
            label = element.findtext("utf8", "")
            if not is_han(label):
                continue
            key = f"{source['path']}:{index}"
            selected_split = "development" if int(hashlib.sha256(key.encode()).hexdigest()[:8], 16) % 2 == 0 else "validation"
            if split != "all" and selected_split != split:
                continue
            extent = max(int(element.findtext("width", "1000")), int(element.findtext("height", "1000")))
            strokes = [np.array([(float(p.attrib["x"]) / extent, float(p.attrib["y"]) / extent)
                                 for p in stroke.findall("point")], dtype=np.float32)
                       for stroke in element.findall("strokes/stroke")]
            if not strokes or len(strokes) > 48 or any(len(s) not in range(1, 513) for s in strokes):
                continue
            if any(not np.isfinite(s).all() or (s < 0).any() or (s > 1).any() for s in strokes):
                continue
            result.append({"id": key, "label": label, "script": script, "strokes": strokes})
    # Fixed SHA order avoids a Unicode-order/common-character selection bias.
    result.sort(key=lambda sample: hashlib.sha256(sample["id"].encode()).hexdigest())
    return [sample for script in ("simplified", "traditional")
            for sample in [s for s in result if s["script"] == script][:count]]


def rasterize(strokes: list[np.ndarray], padding: float = 0, ink: float = 2.5,
              extent: float = 36, width: int = 96) -> np.ndarray:
    """Pillow supersampling approximates Android antialiasing, not pixel parity."""
    points = np.concatenate(strokes)
    left, top = points.min(axis=0)
    right, bottom = points.max(axis=0)
    scale = extent / max(float(right - left), float(bottom - top), .03)
    offset = np.array([24 - (left + right) * scale / 2, 24 - (top + bottom) * scale / 2])
    factor = 4
    bitmap = Image.new("L", (48 * factor, 48 * factor), 255)
    draw = ImageDraw.Draw(bitmap)
    radius = ink * factor / 2
    for stroke in strokes:
        coordinates = [tuple(point) for point in ((stroke * scale + offset) * factor)]
        if len(coordinates) > 1:
            draw.line(coordinates, fill=0, width=round(ink * factor), joint="curve")
        for x, y in coordinates:
            draw.ellipse((x - radius, y - radius, x + radius, y + radius), fill=0)
    bitmap = bitmap.resize((48, 48), Image.Resampling.LANCZOS)
    plane = np.full((48, width), padding, dtype=np.float32)
    plane[:, :48] = np.asarray(bitmap, dtype=np.float32) / 127.5 - 1
    return np.stack((plane, plane, plane))[None, ...]


def legacy_candidates(output: np.ndarray, characters: list[str]) -> list[str]:
    values = output[0]
    path = values.argmax(axis=1)
    greedy = "".join(characters[index] for position, index in enumerate(path)
                     if index != 0 and is_han(characters[index])
                     and (position == 0 or index != path[position - 1]))
    indices = [i for i, value in enumerate(characters) if is_han(value)]
    scores = values[:, indices].max(axis=0)
    ranked = [characters[indices[index]] for index in np.argsort(-scores, kind="stable")[:8]]
    return list(dict.fromkeys(([greedy] if is_han(greedy) else []) + ranked))[:8]


def candidates(output: np.ndarray, characters: list[str]) -> list[str]:
    """Exact CTC probability of blank* character+ blank*, with per-step scaling."""
    if output.ndim != 3 or output.shape[0] != 1 or output.shape[1] not in range(1, 257) or output.shape[2] != len(characters):
        return []
    if not np.isfinite(output).all() or (output < 0).any() or (output > 1).any():
        return []
    if not ((output.sum(axis=2) >= .98) & (output.sum(axis=2) <= 1.02)).all():
        return []
    values = output[0].astype(np.float64)
    before = 1.0
    inside = np.zeros(values.shape[1])
    after = np.zeros(values.shape[1])
    for row in values:
        after = (after + inside) * row[0]
        inside = (inside + before) * row
        inside[0] = after[0] = 0
        before *= row[0]
        scale = max(before, inside.max(), after.max())
        if scale == 0:
            return []
        before /= scale
        inside /= scale
        after /= scale
    scores = inside + after
    scores[0] = 0
    indices = [i for i, value in enumerate(characters) if is_han(value)]
    ranked = sorted(indices, key=lambda i: -scores[i])
    if not ranked or scores[ranked[0]] <= before:
        return []
    # Reject a dominant non-Han class (punctuation, Latin, space), but retain Han alternatives.
    if not is_han(characters[int(scores.argmax())]):
        return []
    return list(dict.fromkeys(characters[i] for i in ranked if scores[i] > 0))[:8]
