"""Check exact sparse rank boundaries and isolation of failed public carriers."""
import argparse
from pathlib import Path
import sys
from tempfile import TemporaryDirectory

sys.path.insert(0, str(Path(__file__).resolve().parent))
from prose_ngram import CapacityError
from scene_roundtrip import MODEL, crypt, load_routes, receive_file, reject
from scene_word_model import encode_body, load_model
from sparse_verse import SparseVerse
from verse_scene_probe import layout, parse


def rank_controls(routes):
    # For radix two, there are 254 nonempty choices shorter than eight lines.
    zero, one = "一" * 5, "丁" * 5
    tiny = SparseVerse(5, [zero, one])
    for packet, choices in ((b"\x00", [one] + [zero] * 7), (b"\xff", [zero] * 9)):
        if tiny.render(packet) != layout(choices) or tiny.recover(layout(choices)) != packet:
            raise ValueError("length bucket boundary or digit order")
    for name, route in routes.items():
        for size in (1, 2, 63, 64, 65, route.guaranteed):
            width_budget = route.worst(size)
            if route.guaranteed_in(width_budget) < size or route.guaranteed_in(width_budget - 1) >= size:
                raise ValueError("incorrect partial budget for the worst envelope")
            for packet in (bytes(size), bytes([255]) * size, bytes(range(256))[:size].rjust(size, b"\0")):
                text = route.render(packet)
                if route.recover(text) != packet or len(text.encode()) > route.worst(size):
                    raise ValueError(f"sparse recovery or capacity bound: {name}")
        reject(lambda: route.render(b"\xff" * (route.guaranteed + 1)), "first non-guaranteed worst case")
        if route.pair_count:
            lines = parse(text, route.width)
            reject(lambda: route.recover(layout(lines[:1])), "missing mandatory second line")
            reject(lambda: route.recover(layout([lines[0], lines[0], *lines[2:]])), "repeated required line")
            if route.ordered and tuple(reversed(lines[:2])) not in route.ordered:
                reject(lambda: route.recover(layout([lines[1], lines[0], *lines[2:]])), "reversed source couplet")


def main(source):
    routes, profiles, _ = load_routes(source)
    rank_controls(routes)
    route, digest = routes["5-sparse-couplet"], profiles["5-sparse-couplet"]
    word, codecs = load_model(MODEL.read_bytes())
    try:
        encode_body(b"x" * 2048, word, codecs)
    except CapacityError:
        pass
    else:
        raise ValueError("expected frozen model's literal packet to exceed body capacity")
    raw = "公开𠀋🙂e\u0301\r\n".encode()
    carrier = route.render(crypt("seal", digest, encode_body(raw, word, codecs)))
    with TemporaryDirectory() as directory:
        path = Path(directory) / "carrier.txt"
        target = path.with_suffix(".bin")
        for damaged in (bytes([255]), b"x" * 16385, carrier.rstrip("。").encode()):
            path.write_bytes(damaged)
            status = receive_file(path, target, route, digest, word, codecs)
            if status != "receive-rejected" or target.exists():
                raise ValueError("damaged input escaped per-file rejection")
        path.write_bytes(carrier.encode())
        if receive_file(path, target, route, digest, word, codecs) != "recovered" or target.read_bytes() != raw:
            raise ValueError("failed to recover valid input after rejections")
    print("All six sparse rank limits, literal body overflow and mixed-batch receive controls passed.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    main(parser.parse_args().source)
