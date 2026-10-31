"""Stage pinned PUBLIC OCR models for the isolated benchmark; never ships in the IME."""

from pathlib import Path
import hashlib
import shutil
from urllib.request import urlopen


ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "build/ocr-evaluation/android-assets/ocr"
DETECTOR_URL = "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.10.0/onnx/PP-OCRv5/det/ch_PP-OCRv5_det_mobile.onnx"
FILES = {
    "rec.onnx": ("inference.onnx", 16_534_782, "da72dc72ca4dc220df0dfde68c1dedc31c58d3e76a25871122e5056227d50092"),
    "characters.txt": ("characters.txt", 74_012, "d1979e9f794c464c0d2e0b70a7fe14dd978e9dc644c0e71f14158cdf8342af1b"),
    "det.onnx": (None, 4_819_576, "4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae"),
}


def valid(path, size, digest):
    return path.is_file() and path.stat().st_size == size and hashlib.sha256(path.read_bytes()).hexdigest() == digest


def main():
    TARGET.mkdir(parents=True, exist_ok=True)
    for name, (source, size, digest) in FILES.items():
        target = TARGET / name
        if valid(target, size, digest):
            continue
        temporary = target.with_suffix(target.suffix + ".part")
        try:
            if source:
                existing = ROOT / "build/handwriting-model" / source
                if not valid(existing, size, digest):
                    raise RuntimeError("Prepare the existing pinned handwriting resources first")
                shutil.copyfile(existing, temporary)
            else:
                with urlopen(DETECTOR_URL, timeout=45) as response, temporary.open("wb") as output:
                    total = 0
                    while chunk := response.read(65_536):
                        total += len(chunk)
                        if total > size:
                            raise RuntimeError("Detector exceeds pinned size")
                        output.write(chunk)
            if not valid(temporary, size, digest):
                raise RuntimeError("OCR research resource failed verification")
            temporary.replace(target)
        finally:
            temporary.unlink(missing_ok=True)
    print("Verified OCR research resources: 21,428,370 bytes; production integration is not approved")


if __name__ == "__main__":
    main()
