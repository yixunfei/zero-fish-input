"""Regenerate reviewed kernels from both original graphs and CPU optimization levels."""
from pathlib import Path
import hashlib
import sys
import onnxruntime as ort

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'build/onnxruntime-src/tools/python'))
from create_reduced_build_config import create_config_from_onnx_models


def prepare():
    if ort.__version__ != '1.26.0':
        raise ValueError('Use pinned host runtime 1.26.0')
    models = [Path('build/model-evaluation/android-assets/mini-int8/model.onnx'),
              Path('build/handwriting-model/inference.onnx')]
    hashes = ['5fb4dbe2c618e8757258253e10481ea9181e8a7b9a8efea03ee70c3a5ca19446',
              'da72dc72ca4dc220df0dfde68c1dedc31c58d3e76a25871122e5056227d50092']
    for index, (path, checksum) in enumerate(zip(list(models), hashes)):
        if hashlib.sha256(path.read_bytes()).hexdigest() != checksum:
            raise ValueError('Unreviewed source model')
        for level in (ort.GraphOptimizationLevel.ORT_ENABLE_BASIC,
                      ort.GraphOptimizationLevel.ORT_ENABLE_EXTENDED, ort.GraphOptimizationLevel.ORT_ENABLE_ALL):
            output = Path('build/onnx-reduced') / f'optimized-{index}-{level.value}.onnx'
            output.parent.mkdir(parents=True, exist_ok=True)
            options = ort.SessionOptions()
            options.log_severity_level = 4
            options.optimized_model_filepath = str(output)
            options.graph_optimization_level = level
            ort.InferenceSession(str(path), options, providers=['CPUExecutionProvider'])
            models.append(output)
    create_config_from_onnx_models(models, Path('tools/onnxruntime/required-operators.config'))


if __name__ == '__main__':
    prepare()
