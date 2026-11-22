"""Send or verify the public natural-wording trial fixture with the independent host codec."""
import argparse
from hashlib import sha256
from pathlib import Path

from binary_prototype import CRYPTO, bounded_file, deflate, helper, inflate, layout, read_ciphertext
from compression_probe import pack
from joint_codec import write_new
from p2_theme_pools import build_pools
from prepare_android_verse import BODY_SHA, DICTIONARY_SHA, MAPPING_SHA, OPENING_SHA, verified


PUBLIC_KEY = bytes(range(32))


def load_profile(directory):
    opening = verified(bounded_file(directory / "opening.bin", 790740), OPENING_SHA)
    mapping = verified(bounded_file(directory / "gb-map.bin", 50364), MAPPING_SHA)
    dictionary = verified(bounded_file(directory / "gb18030.zdict", 15021), DICTIONARY_SHA)
    digest = sha256(b"ZeroInput/natural-wording-trial/profile/v1\0")
    for asset in (opening, mapping, dictionary):
        digest.update(sha256(asset).digest())
    text = opening.decode("utf-16-be")
    _, _, pools = build_pools([text[i:i + 5] for i in range(0, len(text), 5)])
    return digest.digest(), dictionary, pools


def render(pools, ciphertext):
    plain = pack(pools, "linked", ciphertext)
    return layout(plain[:-1].split("。")).encode("utf-8")


def verify(carrier, profile, expected):
    digest, dictionary, pools = profile
    envelope = read_ciphertext(pools, carrier.decode("utf-8"))
    if render(pools, envelope) != carrier:
        raise ValueError("noncanonical public carrier")
    packet = helper([CRYPTO, "open-wording-trial"], PUBLIC_KEY + digest + envelope)
    size = int.from_bytes(packet[1:3], "big")
    if len(packet) < 4 or packet[0] != 4 or size != len(expected):
        raise ValueError("unexpected public packet")
    restored = inflate(packet[3:], 4 * size, dictionary).decode("gb18030").encode("utf-8")
    if restored != expected:
        raise ValueError("public fixture byte mismatch")
    print(f"Byte-exact recovery of {size} B; envelope {len(envelope)} B; carrier {len(carrier)} B")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("send", "verify"))
    parser.add_argument("assets", type=Path)
    parser.add_argument("carrier", type=Path)
    args = parser.parse_args()
    expected = verified(bounded_file(args.assets / "body.txt", 2048), BODY_SHA)
    profile = load_profile(args.assets)
    if args.command == "send":
        digest, dictionary, pools = profile
        stream = deflate(expected.decode("utf-8").encode("gb18030"), dictionary)
        packet = bytes([4]) + len(expected).to_bytes(2, "big") + stream
        envelope = helper([CRYPTO, "seal-wording-trial"], PUBLIC_KEY + digest + packet)
        carrier = render(pools, envelope)
        verify(carrier, profile, expected)
        write_new(args.carrier, carrier)
    else:
        verify(bounded_file(args.carrier, 16384), profile, expected)


if __name__ == "__main__":
    main()
