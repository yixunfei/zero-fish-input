"""Replay short keys on the isolated locked public Rime deployment."""
import re
from time import perf_counter_ns

from p2_bits import KEY, MAX_KEYS, MAX_KEY_LENGTH, MAX_RANK, Token
from p2_segments import sentence_spans
from rime_location_codec import HAN, dictionary_rows


def build_readings(spec):
    singles = {}
    for _, word, spelling, weight in dictionary_rows(dict(spec, tables=["zi"])):
        if len(word) == 1 and HAN.fullmatch(word) and re.fullmatch(r"[a-z]+", spelling):
            rank = weight, spelling
            if rank > singles.get(word, (-1, "")):
                singles[word] = rank
    return {word: spelling for word, (_, spelling) in singles.items()}


def query_plan(raw, terms, readings):
    text = raw.decode("utf-8")
    sentences = set(sentence_spans(text))
    planned = {}
    for match in HAN.finditer(text):
        run = match.group()
        syllables = [readings.get(char) for char in run]
        for left in range(len(run)):
            for right in range(left + 3, min(len(run), left + MAX_KEY_LENGTH) + 1):
                parts = syllables[left:right]
                if not all(parts):
                    break
                word = run[left:right]
                span = match.start() + left, match.start() + right
                full_run = left == 0 and right == len(run)
                priority = 0 if span in sentences else 1 if full_run else 2 if word in terms else 3
                for key in sorted({"".join(p[0] for p in parts), "".join(parts)}):
                    if len(key) > MAX_KEY_LENGTH:
                        continue
                    score = priority, -len(word), span[0], len(key), key
                    if key not in planned:
                        planned[key] = score, set()
                    best, targets = planned[key]
                    targets.add((span, word))
                    planned[key] = min(best, score), targets
    keys = sorted(planned, key=lambda key: planned[key][0])[:MAX_KEYS]
    return {key: planned[key][1] for key in keys}


def replay_terms(raw, terms, readings, query, layout):
    """Choose the cheapest exact replay for each offered public word."""
    start = perf_counter_ns()
    plan = query_plan(raw, terms, readings)
    planned = perf_counter_ns()
    cache = query(plan) if plan else {}
    queried = perf_counter_ns()
    best, inverse, matched_spans = {}, {}, set()
    exact, first = 0, 0
    for key, targets in plan.items():
        candidates = cache[key]
        if len(candidates) > MAX_RANK:
            raise ValueError("native candidate depth")
        for span, word in targets:
            if word not in candidates:
                continue
            rank = candidates.index(word)
            exact += 1
            first += rank == 0
            matched_spans.add(span)
            value = key, rank
            inverse[value] = word
            cost = layout.token_bits(Token(KEY, value)), rank, key
            if word not in best or cost < best[word][0]:
                best[word] = cost, value
    events = {word: pair[1] for word, pair in best.items()}
    sentences = set(sentence_spans(raw.decode("utf-8")))
    queried_spans = {span for targets in plan.values() for span, _ in targets}
    stats = {"queries": len(plan), "targets": sum(map(len, plan.values())), "exact_targets": exact,
             "first_targets": first, "exact_spans": len(matched_spans),
             "sentences_queried": len(queried_spans & sentences),
             "sentence_offers": len(matched_spans & sentences),
             "plan_ms": (planned - start) / 1e6, "query_ms": (queried - planned) / 1e6}
    return events, inverse, cache, stats
