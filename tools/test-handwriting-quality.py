"""Regression checks for public handwriting evaluation and packed model parity."""

import hashlib
import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import unittest

import numpy as np
from handwriting_quality import candidates, load_samples, rasterize
from handwriting_stroke_model import PackedStrokeModel, StrokeModel, extract, fuse


ROOT = Path(__file__).resolve().parents[1]


class HandwritingQualityTest(unittest.TestCase):
    def test_single_character_ctc_is_not_a_frame_maximum(self):
        output = np.array([[[.05, .55, .4], [.1, .1, .8]]], dtype=np.float32)
        self.assertEqual(["\u6587", "\u4e2d"], candidates(output, ["", "\u4e2d", "\u6587"]))
        self.assertEqual([], candidates(np.array([[[.99, .006, .004]]]), ["", "\u4e2d", "\u6587"]))
        output[0, 0, 0] = np.nan
        self.assertEqual([], candidates(output, ["", "\u4e2d", "\u6587"]))

    def test_raster_preserves_square_geometry_and_uses_zero_right_padding(self):
        first = np.array([[.1, .2], [.5, .4]], dtype=np.float32)
        second = np.array([[.3, .4], [.9, .7]], dtype=np.float32)
        image = rasterize([first])
        self.assertEqual((1, 3, 48, 96), image.shape)
        self.assertTrue((image[:, :, :, 48:] == 0).all())
        # Raster coordinates may straddle a supersampling rounding boundary.
        self.assertLess(float(np.abs(image - rasterize([second])).mean()), .005)

    def test_stroke_features_match_public_horizontal_equations(self):
        features = extract([np.array([[.1, .5], [.9, .5]], dtype=np.float32)])
        self.assertAlmostEqual(9, float(features[1]), places=5)
        self.assertAlmostEqual(-4.5, float(features[3]), places=5)
        self.assertEqual(10, features[2_000_001])

    def test_model_layout_conversion_preserves_scores(self):
        specification = importlib.util.spec_from_file_location("prepare_strokes", Path(__file__).with_name("prepare-handwriting-stroke-model.py"))
        preparation = importlib.util.module_from_spec(specification)
        specification.loader.exec_module(preparation)
        labels = ["\u4e2d", "\u9ad4"]
        payload = bytearray(struct.pack("<III", 0, 1, 2))
        for label, bias, weight in zip(labels, [-.1, -.2], [.3, .6]):
            payload += struct.pack("<16sf", label.encode("utf-8"), bias)
            payload += struct.pack("<ififif", 0, -bias, 2_000_001, weight, -1, 0)
        struct.pack_into("<I", payload, 0, len(payload) ^ 0x0EF71821)
        with tempfile.TemporaryDirectory() as folder:
            original = Path(folder) / "source.model"
            packed = Path(folder) / "runtime.zsh"
            original.write_bytes(payload)
            preparation.pack(bytes(payload), packed)
            strokes = [np.array([[.1, .5], [.9, .5]], dtype=np.float32)]
            self.assertEqual(StrokeModel(original).recognize(strokes), PackedStrokeModel(packed).recognize(strokes))

    def test_pinned_fixture_selection_is_disjoint_and_includes_twenty_independent_strokes(self):
        directory = ROOT / "build/handwriting-fixtures"
        self.assertTrue((directory / "sources.json").is_file(), "Run prepare-handwriting-model.py --include-quality-fixtures")
        development = load_samples(directory, "development", 256)
        validation = load_samples(directory, "validation", 256)
        self.assertFalse({s["id"] for s in development} & {s["id"] for s in validation})
        all_samples = load_samples(directory, "all", 10_000)
        self.assertEqual(20, len([s for s in all_samples if "/test/" in s["id"]]))
        sources = json.loads((directory / "sources.json").read_text(encoding="utf-8"))
        self.assertEqual(hashlib.sha256((directory / "sources.json").read_bytes()).hexdigest(),
                         hashlib.sha256(Path(__file__).with_name("handwriting-fixture-sources.json").read_bytes()).hexdigest())
        self.assertEqual(23, len(sources))

    def test_fusion_keeps_stroke_and_visual_alternatives(self):
        self.assertEqual(["\u5b78", "\u5b66", "\u5b57"], fuse([
            [("\u5b66", .8), ("\u5b57", .3)], [("\u5b78", 1.2)]
        ], ["\u5b66", "\u5b57"]))


if __name__ == "__main__":
    unittest.main()
