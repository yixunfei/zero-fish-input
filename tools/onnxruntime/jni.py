"""Compile matching CPU-only JNI entry points without enabling optional providers."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]


def build_jni(source, ndk, ninja, abi):
    output = ROOT / 'build/onnx-jni'
    headers = output / 'headers'
    classes = output / 'classes'
    headers.mkdir(parents=True, exist_ok=True)
    classes.mkdir(parents=True, exist_ok=True)
    java = list((source / 'java/src/main/java').rglob('*.java'))
    java += list((source / 'java/src/main/jvm').rglob('*.java'))
    subprocess.run(['javac', '-encoding', 'UTF-8', '--release', '8', '-h', str(headers),
                    '-d', str(classes)] + [str(path) for path in java], check=True)
    directory = output / abi
    library = ROOT / 'build/onnx-reduced' / abi / 'MinSizeRel/libonnxruntime.so'
    subprocess.run(['cmake', '-S', str(ROOT / 'tools/onnxruntime/jni'), '-B', str(directory), '-G', 'Ninja',
                    '-DCMAKE_MAKE_PROGRAM=' + str(ninja),
                    '-DCMAKE_TOOLCHAIN_FILE=' + str(ndk / 'build/cmake/android.toolchain.cmake'),
                    '-DANDROID_ABI=' + abi, '-DANDROID_PLATFORM=android-26', '-DCMAKE_BUILD_TYPE=MinSizeRel',
                    '-DORT_SOURCE=' + str(source), '-DORT_HEADERS=' + str(headers),
                    '-DORT_LIBRARY=' + str(library)], check=True)
    subprocess.run(['cmake', '--build', str(directory), '--parallel', '6'], check=True)
    return directory / 'libonnxruntime4j_jni.so'
