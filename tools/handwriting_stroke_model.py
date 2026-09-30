"""Local evaluator for the pinned Tegaki Zinnia model.

Feature equations follow Zinnia (BSD-3-Clause), Copyright (c) 2005-2007 Taku Kudo.
The model is separate LGPL-2.1 data. See docs/handwriting-quality-validation.md.
"""

from pathlib import Path
import math
import struct

import numpy as np


class StrokeModel:
    def __init__(self, path: Path):
        data = path.read_bytes()
        magic, version, count = struct.unpack_from("<III", data)
        if magic ^ 0x0EF71821 != len(data) or version != 1 or count > 20_000:
            raise ValueError("Invalid stroke model header")
        self.labels = []
        self.biases = np.empty(count, dtype=np.float32)
        self.starts = np.empty(count, dtype=np.int64)
        indices = np.empty(len(data) // 8, dtype=np.int32)
        weights = np.empty(len(data) // 8, dtype=np.float32)
        cursor = 12
        written = 0
        for label_index in range(count):
            label, bias = struct.unpack_from("<16sf", data, cursor)
            cursor += 20
            self.labels.append(label.split(b"\0", 1)[0].decode("utf-8"))
            self.biases[label_index] = bias
            self.starts[label_index] = written
            while True:
                index, value = struct.unpack_from("<if", data, cursor)
                cursor += 8
                if index == -1:
                    break
                if index not in range(2_000_100) or not math.isfinite(value):
                    raise ValueError("Invalid stroke model feature")
                indices[written] = index
                weights[written] = value
                written += 1
        if cursor != len(data):
            raise ValueError("Trailing stroke model bytes")
        self.indices = indices[:written].copy()
        self.weights = weights[:written].copy()

    def recognize(self, strokes: list[np.ndarray], normalize: bool = True) -> list[tuple[str, float]]:
        features = extract(strokes, normalize)
        values = (features[self.indices] * self.weights).astype(np.float64)
        scores = self.biases + np.add.reduceat(values, self.starts)
        ranked = np.argsort(-scores, kind="stable")[:8]
        return [(self.labels[i], float(scores[i])) for i in ranked]


class PackedStrokeModel:
    def __init__(self, path: Path):
        self.data = path.read_bytes()
        magic, version, labels, features, postings, largest = struct.unpack_from("<4sIIIII", self.data)
        if magic != b"ZSH1" or version != 1 or len(self.data) != 28 + labels * 8 + features * 8 + postings * 6:
            raise ValueError("Invalid packed stroke model")
        label_data = np.frombuffer(self.data, dtype=[("code", "<u4"), ("bias", "<f4")], count=labels, offset=24)
        self.labels = [chr(code) for code in label_data["code"]]
        self.biases = label_data["bias"]
        start = 24 + labels * 8
        feature_ids = np.frombuffer(self.data, dtype="<u4", count=features, offset=start)
        offsets = np.frombuffer(self.data, dtype="<u4", count=features + 1, offset=start + features * 4)
        start += features * 8 + 4
        self.classes = np.frombuffer(self.data, dtype="<u2", count=postings, offset=start)
        self.weights = np.frombuffer(self.data, dtype="<f4", count=postings, offset=start + postings * 2)
        self.entry_features = np.repeat(feature_ids, np.diff(offsets).astype(np.int64))

    def recognize(self, strokes: list[np.ndarray], normalize: bool = True) -> list[tuple[str, float]]:
        features = extract(strokes, normalize)
        products = features[self.entry_features] * self.weights
        scores = (self.biases + np.bincount(self.classes, weights=products, minlength=len(self.labels))).astype(np.float32)
        ranked = np.argsort(-scores, kind="stable")[:8]
        return [(self.labels[i], float(scores[i])) for i in ranked]


def fuse(stroke_results: list[list[tuple[str, float]]], image_results: list[str]) -> list[str]:
    stroke = list(dict.fromkeys(label for label, _ in sorted(
        [candidate for model in stroke_results[:2] for candidate in model[:8]], key=lambda item: -item[1])))[:8]
    result = []
    for index in range(8):
        for candidates in (stroke, image_results):
            if index < len(candidates) and candidates[index] not in result:
                result.append(candidates[index])
    return result[:16]


def extract(strokes: list[np.ndarray], normalize: bool = True) -> np.ndarray:
    features = np.zeros(2_000_100, dtype=np.float32)
    features[0] = 1
    if normalize:
        points = np.concatenate(strokes)
        low, high = points.min(axis=0), points.max(axis=0)
        scale = .9 / max(float((high - low).max()), .03)
        strokes = [(stroke - (low + high) / 2) * scale + .5 for stroke in strokes]
    for stroke_id, stroke in enumerate(strokes):
        vertices(features, stroke, 0, len(stroke) - 1, stroke_id, 0)
        if stroke_id:
            basic(features, 100_000 + stroke_id * 1000, strokes[stroke_id - 1][-1], stroke[0])
    features[2_000_000] = len(strokes)
    features[2_000_000 + len(strokes)] = 10
    return features


def vertices(features: np.ndarray, points: np.ndarray, first: int, last: int, stroke: int, node: int) -> None:
    if node > 50:
        return
    basic(features, stroke * 1000 + node * 20, points[first], points[last])
    a, b = points[last] - points[first]
    denominator = float(a * a + b * b)
    if first == last or denominator == 0:
        return
    c = float(points[last][1] * points[first][0]) - float(points[last][0] * points[first][1])
    distances = np.abs(a * points[first:last, 1] - b * points[first:last, 0] + c)
    best = int(distances.argmax()) + first
    if float(distances[best - first]) ** 2 / denominator > .001 and first < best < last:
        vertices(features, points, first, best, stroke, node * 2 + 1)
        vertices(features, points, best, last, stroke, node * 2 + 2)


def basic(features: np.ndarray, offset: int, first: np.ndarray, last: np.ndarray) -> None:
    dx, dy = last - first
    features[offset + 1:offset + 13] = (
        10 * math.hypot(dx, dy), math.atan2(dy, dx),
        10 * (first[0] - .5), 10 * (first[1] - .5), 10 * (last[0] - .5), 10 * (last[1] - .5),
        math.atan2(first[1] - .5, first[0] - .5), math.atan2(last[1] - .5, last[0] - .5),
        10 * math.hypot(first[0] - .5, first[1] - .5), 10 * math.hypot(last[0] - .5, last[1] - .5),
        5 * dx, 5 * dy,
    )
