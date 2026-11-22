"""Integer word n-gram continuation for public, host-only prose fixtures."""

from collections import Counter, defaultdict
import json


START = "<s>"
STOP = "\u3002"
CARRIER_BYTES = 16_384
CARRIER_UNITS = 8_192
MAX_SENTENCE_TOKENS = 12
MAX_ENVELOPE_BYTES = 2_548
MAX_OPTIONS = 8


class CapacityError(ValueError):
    pass


def is_han(word):
    return bool(word) and all("\u4e00" <= char <= "\u9fff" for char in word)


def train(sentences, order):
    if order not in (2, 3):
        raise ValueError("expected a bigram or trigram")
    counts = defaultdict(Counter)
    for sentence in sentences:
        words = sentence.split(" ")
        if not 1 <= len(words) <= MAX_SENTENCE_TOKENS or not all(map(is_han, words)):
            raise ValueError("training needs bounded, space-segmented Han sentences")
        history = (START,) * (order - 1)
        for word in words + [STOP]:
            for width in range(1, order):
                counts[history[-width:]][word] += 1
            history = (*history[1:], word)
    return counts


def shortlist(counts):
    selected = []
    for word, frequency in sorted(counts.items(), key=lambda item: (-item[1], item[0])):
        if not any(word.startswith(old) or old.startswith(word) for old, _ in selected):
            selected.append((word, frequency))
        if len(selected) == MAX_OPTIONS:
            break
    return selected


def freeze(rows, order):
    table = [[list(context), words] for context, words in sorted(rows.items())]
    return json.dumps([order, table], ensure_ascii=False, separators=(",", ":")).encode("utf-8")


class ProseNgram:
    def __init__(self, resource):
        order, table = json.loads(resource)
        self.rows = {tuple(context): tuple(word for word, _ in words) for context, words in table}
        self.start = (START,) * (order - 1)
        self.cache = {}
        if len(self.choices(self.start, MAX_SENTENCE_TOKENS)) < 2:
            raise ValueError("scene needs at least two reachable opening words")

    def choices(self, history, remaining):
        key = (history, remaining)
        if key not in self.cache:
            self.cache[key] = self.reachable_choices(history, remaining)
        return self.cache[key]

    def reachable_choices(self, history, remaining):
        for index in range(len(history)):
            options = self.rows.get(history[index:], ())
            reachable = tuple(
                word for word in options
                if word == STOP or (remaining > 0 and self.choices((*history[1:], word), remaining - 1))
            )
            if reachable:
                return reachable
        return ()

    def continue_text(self, words):
        history = self.start
        remaining = MAX_SENTENCE_TOKENS
        pieces = []
        for word in words:
            if word == STOP or word not in self.choices(history, remaining):
                raise ValueError("prefix is outside the scene model")
            pieces.append(word)
            history = (*history[1:], word)
            remaining -= 1
        while True:
            word = self.choices(history, remaining)[0]
            pieces.append(word)
            if word == STOP:
                return "".join(pieces)
            history = (*history[1:], word)
            remaining -= 1

    def render(self, packet):
        if not 1 <= len(packet) <= MAX_ENVELOPE_BYTES:
            raise ValueError("invalid synthetic envelope length")
        number = int.from_bytes(b"\x01" + packet, "big")
        text = []
        byte_count, units = 0, 0
        history = self.start
        remaining = MAX_SENTENCE_TOKENS
        while True:
            choices = self.choices(history, remaining)
            number, digit = divmod(number, len(choices))
            word = choices[digit]
            # Compiled words and punctuation are all in the BMP and use three UTF-8 bytes per unit.
            units += len(word)
            byte_count += 3 * len(word)
            if byte_count > CARRIER_BYTES or units > CARRIER_UNITS:
                raise CapacityError("synthetic envelope exceeds the prose carrier limit")
            text.append(word)
            if word == STOP:
                if number == 0:
                    return "".join(text)
                history, remaining = self.start, MAX_SENTENCE_TOKENS
            else:
                history = (*history[1:], word)
                remaining -= 1

    def recover(self, text):
        if not 1 <= len(text) <= CARRIER_UNITS or not text.endswith(STOP) or len(text.encode()) > CARRIER_BYTES:
            raise ValueError("invalid synthetic prose carrier length or ending")
        position, number, span = 0, 0, 1
        history = self.start
        remaining = MAX_SENTENCE_TOKENS
        while position < len(text):
            choices = self.choices(history, remaining)
            for digit, word in enumerate(choices):
                if text.startswith(word, position):
                    break
            else:
                raise ValueError("unexpected word in synthetic prose")
            number += digit * span
            span *= len(choices)
            position += len(word)
            if word == STOP:
                history, remaining = self.start, MAX_SENTENCE_TOKENS
            else:
                history = (*history[1:], word)
                remaining -= 1
        framed = number.to_bytes((number.bit_length() + 7) // 8, "big")
        if not 2 <= len(framed) <= MAX_ENVELOPE_BYTES + 1 or framed[0] != 1:
            raise ValueError("invalid synthetic envelope frame")
        packet = framed[1:]
        if self.render(packet) != text:
            raise ValueError("noncanonical synthetic prose")
        return packet
