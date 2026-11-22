"""Run the public fixture oracle in a throwaway emulator directory (no installed IME)."""
import hashlib
import json
import os
from contextlib import contextmanager
from functools import partial
from pathlib import Path
import re
import subprocess
import tempfile

from verse import ROOT


CONFIG_HASHES = {
    "build/default.yaml": "335bfc96758491ff942aeaef95a8f059db69b4e89f1258ba635e91cf4ca9cabc",
    "build/zeroinput_pinyin.schema.yaml": "c0c4bfff93d640d49e81c1b091fbac2cc3d35ec0ca0b5e277e52032270af22d5",
    "opencc/t2s.json": "e585f482a367e82b74aa236645740fa35908cadb46ebe443362804f64c52f7a1",
    "opencc/TSCharacters.txt": "6b5a0a799bea2bb22c001f635eaa3fc2904310f0c08addbff275477a80ecf09a",
    "opencc/TSPhrases.txt": "b2ef895dd4953b4bb77fc8ef8d26a2a9ca6d43a760ed9a1d767672cfafa6324f",
}
REMOTE_PATTERN = r"/data/local/tmp/ziverse\.[a-zA-Z0-9]{8}"


def build(exe, native):
    sdk = Path(os.environ["ANDROID_HOME"])
    clang = sdk / "ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe"
    libs = ["librime/lib/librime.a", "boost/libs/container/libboost_container.a",
            "yaml-cpp/libyaml-cpp.a", "leveldb/libleveldb.a", "opencc/src/libopencc.a",
            "marisa/libmarisa.a"]
    subprocess.run([clang, "--target=x86_64-linux-android26", "-std=c++17", "-O2", "-s",
                    "-static-libstdc++", "-I", ROOT / "third_party/librime/src",
                    Path(__file__).with_name("rime_rank_probe.cc"), *[native / lib for lib in libs],
                    "-llog", "-landroid", "-latomic", "-lm", "-o", exe], check=True)


@contextmanager
def deployment(serial, native):
    adb = ["adb", "-s", serial]
    deploy = ROOT / "build/rime-prebuilt-linux/input"
    manifest = json.loads((ROOT / "engine-rime/prebuilt/rime/prebuilt/manifest.json").read_bytes())
    with tempfile.TemporaryDirectory() as folder:
        exe = Path(folder) / "oracle"
        build(exe, native)
        remote = subprocess.check_output(adb + ["shell", "mktemp", "-d",
                                          "/data/local/tmp/ziverse.XXXXXXXX"]).decode().strip()
        if not re.fullmatch(REMOTE_PATTERN, remote):
            raise ValueError("invalid scratch directory")
        try:
            subprocess.run(adb + ["shell", "mkdir", remote+"/build", remote+"/user", remote+"/opencc"],
                           check=True)

            def push(path, dest):
                if dest in CONFIG_HASHES and hashlib.sha256(path.read_bytes()).hexdigest() != CONFIG_HASHES[dest]:
                    raise ValueError("unrecognized default Rime configuration")
                subprocess.run(adb + ["push", str(path), remote+"/"+dest], check=True,
                               capture_output=True)

            for name, record in manifest["files"].items():
                bin_name = name.removesuffix(".xz")
                path = deploy / "user/build" / bin_name
                if hashlib.sha256(path.read_bytes()).hexdigest() != record["sha256"]:
                    raise ValueError("compiled dictionary mismatch")
                push(path, "build/"+bin_name)
            for name in ("default.yaml", "zeroinput_pinyin.schema.yaml"):
                push(deploy / "user/build" / name, "build/"+name)
            push(deploy / "shared/opencc/t2s.json", "opencc/t2s.json")
            for name in ("TSCharacters.txt", "TSPhrases.txt"):
                push(ROOT / "engine-rime/build/generated/openccAssets/rime/opencc" / name, "opencc/"+name)
            push(exe, "oracle")
            subprocess.run(adb + ["shell", "chmod", "700", remote+"/oracle"], check=True)

            yield remote
        finally:
            subprocess.run(adb + ["shell", "rm", "-r", remote], check=True)


def query_at(serial, remote, keys):
    if not re.fullmatch(REMOTE_PATTERN, remote):
        raise ValueError("invalid scratch directory")
    keys = sorted(set(keys))
    if not keys:
        return {}
    if any(not re.fullmatch(r"[a-z]{1,64}", key) for key in keys):
        raise ValueError("invalid native key")
    data = ("\n".join(keys)+"\n").encode("ascii")
    # -T disables terminal line processing.
    result = subprocess.run(["adb", "-s", serial, "shell", "-T", f"cd {remote} && ./oracle"],
                            input=data, capture_output=True, timeout=480, check=True)
    rows = [line.split("\t") for line in result.stdout.decode("utf-8").splitlines()]
    if [row[0] for row in rows] != keys:
        raise ValueError("incomplete native replay")
    return {row[0]: row[1:] for row in rows}


@contextmanager
def oracle(serial, native):
    with deployment(serial, native) as remote:
        yield partial(query_at, serial, remote)
