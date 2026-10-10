"""Rebuild the reviewed Mini derivative from pinned upstream inputs before publication."""
import hashlib
import json
from pathlib import Path
import runpy
import sys

ROOT = Path(__file__).resolve().parents[2]


def prepare():
    exporter = runpy.run_path(str(ROOT / 'tools/export-model-benchmark.py'))
    evaluation = exporter['evaluation']
    evaluation.verify_artifacts('mini', evaluation.CACHE / 'mini', download=True)
    previous = sys.argv
    try:
        sys.argv = ['export-model-benchmark.py', '--model', 'mini', '--int8']
        exporter['main']()
    finally:
        sys.argv = previous
    catalog = json.loads((ROOT / 'tools/resources/catalog.json').read_text(encoding='utf-8'))
    pack = next(item for item in catalog['packs'] if item['id'] == 'mini-int8')
    paths = {
        'mini-int8/model.onnx': evaluation.CACHE / 'android-assets/mini-int8/model.onnx',
        'mini-int8/vocab.txt': evaluation.CACHE / 'mini/vocab.txt',
    }
    if {item['name'] for item in pack['files']} != paths.keys():
        raise ValueError('Unexpected reviewed Mini files')
    for item in pack['files']:
        path = paths[item['name']]
        with path.open('rb') as stream:
            checksum = hashlib.file_digest(stream, 'sha256').hexdigest()
        if path.stat().st_size != item['bytes'] or checksum != item['sha256']:
            raise ValueError('Rebuilt Mini differs from the reviewed catalog')


if __name__ == '__main__':
    prepare()
