"""Position lookup, segmentation in the emitted bit cost and coverage accounting."""
from bisect import bisect_left
import re
from unicodedata import category

from p1_positions import ROW_BASE
from p2_bits import ASCII, KEY, LITERAL, Token, row_code, row_token
from rime_location_codec import HAN, runs


SENTENCES = re.compile(r"[^。！？!?\r\n]+")


def cached_terms(index, raw):
    """Reuse the lower bound for repeated prefixes of the same public input."""
    matches, bounds = {}, {}
    for run in set(runs(raw)):
        for start in range(len(run)):
            lower = 0
            for end in range(start + 1, min(len(run), start + 64) + 1):
                word = run[start:end]
                if word not in bounds:
                    prefix = word.encode()
                    match = bisect_left(index.entries, prefix, lower, key=index._word_at)
                    if match == len(index.entries):
                        bounds[word] = None
                    else:
                        hit = index._word_at(match)
                        bounds[word] = match if hit.startswith(prefix) else None
                        if hit == prefix:
                            code = index._code_at(match)
                            matches[word] = code.to_bytes(3 if code >= ROW_BASE else 2, "big")
                lower = bounds[word]
                if lower is None:
                    break
    return matches


def candidate_terms(terms, layout, events):
    if layout is None:
        return {word: (8 * len(code), row_token(code)) for word, code in terms.items()}
    options = {word: row_token(code) for word, code in terms.items()}
    for word, value in events.items():
        token = Token(KEY, value)
        if word not in options or layout.token_bits(token) < layout.token_bits(options[word]):
            options[word] = token
    return {word: (layout.token_bits(token), token) for word, token in options.items()}


def segment(raw, terms, layout=None, events=None):
    if not 1 <= len(raw) <= 2048:
        raise ValueError("original size")
    text = raw.decode("utf-8")
    offsets = [0]
    for char in text:
        offsets.append(offsets[-1] + len(char.encode()))
    size = len(text)
    candidates = candidate_terms(terms, layout, events or {})
    widths = sorted({len(word) for word in candidates})
    literal_header = 16 if layout is None else 10
    costs, path = [0] * (size + 1), [None] * size
    for start in reversed(range(size)):
        best = (literal_header + 8 * (offsets[start + 1] - offsets[start]) + costs[start + 1],
                1, LITERAL, None)
        for end in range(start + 2, min(size, start + 64) + 1):
            amount = offsets[end] - offsets[start]
            if amount > 64:
                break
            option = (literal_header + 8 * amount + costs[end], end - start, LITERAL, None)
            if option < best:
                best = option
        if ord(text[start]) < 128:
            best = min(best, (8 + costs[start + 1], 1, ASCII, None))
        for width in widths:
            end = start + width
            if end > size:
                break
            match = candidates.get(text[start:end])
            if match is not None:
                bits, token = match
                option = (bits + costs[end], width, token.kind, token)
                if option[:3] < best[:3]:
                    best = option
        costs[start], width, kind, token = best
        path[start] = width, kind, token
    result, start = [], 0
    while start < size:
        width, kind, token = path[start]
        end = start + width
        if kind == ASCII:
            token = Token(ASCII, raw[offsets[start]])
        elif kind == LITERAL:
            token = Token(LITERAL, raw[offsets[start]:offsets[end]])
        result.append(token)
        start = end
    return result


def sentence_spans(text):
    for match in SENTENCES.finditer(text):
        token = match.group()
        stripped = token.strip()
        if len(stripped) >= 3 and HAN.fullmatch(stripped):
            start = match.start() + len(token) - len(token.lstrip())
            yield start, start + len(stripped)


def coverage(raw, items, positions, replay_words=None):
    text = raw.decode("utf-8")
    sentences = set(sentence_spans(text))
    words = replay_words or {}
    ranks = []
    seen = set()
    long_terms = long_bytes = row_values = 0
    literals = dict.fromkeys(("ascii", "punctuation", "other"), 0)
    start = 0
    for item in items:
        if item.kind in (ASCII, LITERAL):
            part = chr(item.value) if item.kind == ASCII else item.value.decode("utf-8")
            for char in part:
                group = "ascii" if char.isascii() else ("punctuation" if category(char).startswith("P") else "other")
                literals[group] += len(char.encode())
        else:
            if item.kind == KEY:
                part = words[item.value]
                ranks.append(item.value[1])
            else:
                part = positions.get(row_code(item))
                row_values += 1
            if len(part) >= 3:
                long_terms += 1
                long_bytes += len(part.encode())
            span = start, start + len(part)
            if span in sentences:
                seen.add(span)
        start += len(part)
    return {"sentence_total": len(sentences), "sentences": sorted(seen),
            "long_terms": long_terms, "long_bytes": long_bytes,
            "row_tokens": row_values, "literal_bytes": literals, "ranks": ranks}
