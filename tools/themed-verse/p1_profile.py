"""Load the exact P1 public configuration for a sender or a receiver."""
from hashlib import sha256
import json
from pathlib import Path

from binary_prototype import MODES, bounded_file
from p1_positions import FORWARD_FILE, REVERSE_FILE, PositionIndex
from verse import linked_pool
from word_codec import WordCodec


PROFILE_SHA256 = "151099ba9459617e0f3af72543fb876f8653ef3ae984b6d1218a2b8f361e33db"
PROFILE_NAME = "profile.json"
POOLS = "linked-pools.json"
WORDS = "word-counts.json"
POSITION_DICT = "position-zdict.bin"
UTF8_DICT = "utf8-zdict.bin"
GB_DICT = "gb18030-zdict.bin"
RECEIVE_ASSETS = (POOLS, WORDS, REVERSE_FILE, POSITION_DICT, UTF8_DICT, GB_DICT)
MODE_IDS = {**{mode: i for i, mode in enumerate(MODES)},
            "position": 16, "position-zlib": 17, "position-dict": 18}


class ProfileError(ValueError):
    pass


class LinkedVerse:
    def __init__(self, groups):
        self.groups = tuple(tuple(group) for group in groups)
        self.theme = sorted(set().union(*self.groups))

    def pool(self, mode, previous):
        if mode != "linked":
            raise ValueError("unknown configuration")
        return linked_pool(previous, self.theme, self.groups)


class Profile:
    def __init__(self, folder, *, sending):
        folder = Path(folder)
        self.receive_files = [folder / name for name in (PROFILE_NAME, *RECEIVE_ASSETS)]
        try:
            manifest = bounded_file(folder / PROFILE_NAME, 8192)
            self.digest = sha256(manifest).digest()
            if self.digest.hex() != PROFILE_SHA256:
                raise ValueError("profile id")
            assets = json.loads(manifest)["assets"]

            def load(name):
                entry = assets[name]
                data = bounded_file(folder / name, entry["bytes"])
                if len(data) != entry["bytes"] or sha256(data).hexdigest() != entry["sha256"]:
                    raise ValueError("resource hash")
                return data

            self.verse = LinkedVerse(json.loads(load(POOLS)))
            self.words = WordCodec({bytes.fromhex(word): count
                                    for word, count in json.loads(load(WORDS))})
            self.positions = PositionIndex(load(REVERSE_FILE), load(FORWARD_FILE) if sending else None)
            self.position_dict = load(POSITION_DICT)
            self.dicts = {"utf-8": load(UTF8_DICT), "gb18030": load(GB_DICT)}
        except FileNotFoundError as error:
            raise ProfileError("missing-resource") from error
        except ValueError as error:
            raise ProfileError("profile-mismatch") from error
