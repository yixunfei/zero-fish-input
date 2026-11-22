"""Fixed and negative vectors for the P2 research layouts."""
from binary_prototype import inflate
from p2_bits import (ASCII, KEY, LITERAL, ROW, SHORT, Bits, Layout, Reader, Token,
                     read_ranks, read_sparse, recover)


FREQUENCIES = {SHORT: (0, 1), ROW: (5060, 0)}


def rejected(function, *args):
    try:
        function(*args)
    except ValueError:
        return
    raise AssertionError("invalid fixture accepted")


def check_layouts():
    ascii_a = [Token(ASCII, ord("A"))]
    for name, expected in (("prefix", "001410"), ("bitmap", "001208")):
        layout = Layout(name, FREQUENCIES)
        stream, _ = layout.encode(ascii_a)
        assert stream.hex() == expected and layout.parse(stream, 1) == ascii_a
        rejected(layout.parse, stream[:-1], 1)
        rejected(layout.parse, stream + b"\0", 1)
        rejected(layout.parse, stream[:-1] + bytes([stream[-1] | 1]), 1)
    group = [Token(ASCII, a % 128) for a in range(65)]
    mixed = group + [Token(SHORT, 0), Token(ROW, 5060), Token(LITERAL, "\r\n𠀀🫠".encode())]
    for name in ("prefix", "bitmap", "bitmap-width", "bitmap-sparse", "freq-fixed", "freq-reseg"):
        layout = Layout(name, FREQUENCIES)
        stream, cost = layout.encode(mixed)
        assert sum(cost.values()) == len(stream) * 8 and layout.parse(stream, 95) == mixed
        rejected(layout.parse, stream, 1)
    sparse = Layout("bitmap-sparse", FREQUENCIES)
    # A full uniform block uses the zero-exception representation.
    uniform, cost = sparse.encode(group[:32])
    assert not cost["exception"] and sparse.parse(uniform, 32) == group[:32]
    bits = Bits()
    for value, width in ((1, 1), (0, 2), (2, 3), (1, 2), (2, 2), (1, 2), (3, 2)):
        bits.put(value, width, "exception")
    rejected(read_sparse, Reader(bits.finish()[0]), 4)


def check_keys():
    items = [Token(KEY, ("ab", 0)), Token(KEY, ("ba", 6)), Token(KEY, ("aab", 31))] * 12
    for name in ("key-direct", "key-rank"):
        layout = Layout(name, FREQUENCIES)
        stream, costs = layout.encode(items)
        assert layout.parse(stream, 108) == items and sum(costs.values()) == 8 * len(stream)
        rejected(layout.parse, stream[:-2], 108)
        rejected(recover, [Token(KEY, ("ab", 31))], None, 1, lambda keys: {"ab": ["x"]})
        rejected(layout.encode, [Token(KEY, ("ab", 32))])
    bad_rank = Bits()
    bad_rank.put(1, 1, "exception")
    bad_rank.put(0, 3, "exception")
    bad_rank.put(3, 5, "exception")
    rejected(read_ranks, Reader(bad_rank.finish()[0]), 1)
    rejected(Layout("prefix", FREQUENCIES).parse, bytes.fromhex("001f00"), 2)
    # An authenticated but invalid row must still fail before publication.
    bad_row = [Token(ROW, (1 << 22) - 1)]
    layout = Layout("prefix", FREQUENCIES)
    rejected(layout.parse, layout.encode(bad_row)[0], 1)


def check_inflate():
    import zlib

    rejected(inflate, zlib.compress(b"x" * 8195), 8194)
    rejected(inflate, zlib.compress(b"xx") + b"\x00", 5)
    rejected(inflate, zlib.compress(b"xx")[:-1], 5)


def run():
    check_layouts()
    check_keys()
    check_inflate()


if __name__ == "__main__":
    run()
    print("PASS P2 fixed vectors, ordered keys, bitmap boundaries and malformed streams")
