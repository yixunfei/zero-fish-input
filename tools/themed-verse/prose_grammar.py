"""Compile typed prose rules and render public fixture packets with frozen codebooks."""

from hashlib import sha256
import json
from string import Formatter

from prose_ngram import CapacityError, CARRIER_BYTES, CARRIER_UNITS, MAX_ENVELOPE_BYTES, START, STOP, is_han, train
from word_codec import WordCodec


MAX_PHASE_CHOICES = 2048


def options(part, lexicon):
    if "forms" in part:
        choices = tuple(tuple(form.split(" ")) for form in part["forms"])
        if not choices or not all(is_han(word) for form in choices for word in form):
            raise ValueError("a grammar form contains an empty or non-Han token")
        return choices
    valid = tuple((word,) for word in part["words"] if is_han(word) and word in lexicon)
    if not valid:
        raise ValueError(f"no locked vocabulary supports the {part['role']} slot")
    return valid


class CandidateWeights:
    def __init__(self, sentences, scorer):
        self.counts = train(sentences, 3)
        self.scorer = scorer
        self.scores = {}

    def occurrence(self, prefix, first):
        for width in (2, 1):
            prior = ((START, START) + prefix)[-width:]
            count = self.counts.get(prior, {}).get(first, 0)
            if count:
                return min(count, 3)
        return 0

    def bonuses(self, prefix, choices):
        context = "".join(prefix)[-16:]
        words = tuple(part[0] for part in choices
                      if len(part) == 1 and len(part[0]) == 2 and self.scorer.accepts(part[0]))
        if not context or not self.scorer.accepts(context) or not 2 <= len(words) <= 8:
            return {}
        key = (context, words)
        if key not in self.scores:
            scored = self.scorer.scores(context, words)
            best = max(scored.values())
            self.scores[key] = {word: int(score >= best - 1.5) + int(score >= best - 0.5)
                                for word, score in scored.items()}
        return self.scores[key]


def compile_phase(frames, lexicon, weights):
    leaves = {}
    for frame in frames:
        paths = [((), 1, 0)]
        for part in frame:
            branches = options(part, lexicon)
            if len(paths) * len(branches) > MAX_PHASE_CHOICES:
                raise ValueError("grammar frame exceeds the bounded choice budget")
            expanded = []
            for tokens, count, bonus in paths:
                bonuses = weights.bonuses(tokens, branches)
                for branch in branches:
                    expanded.append((tokens + branch, count + weights.occurrence(tokens, branch[0]),
                                     bonus + bonuses.get(branch[0], 0)))
            paths = expanded
        if len(leaves) + len(paths) > MAX_PHASE_CHOICES:
            raise ValueError("grammar phase exceeds the bounded choice budget")
        for tokens, count, bonus in paths:
            surface = "".join(tokens)
            if surface in leaves:
                raise ValueError(f"ambiguous grammar surface: {surface}")
            leaves[surface] = (count, count + bonus)
    if not 2 <= len(leaves) <= MAX_PHASE_CHOICES:
        raise ValueError("invalid grammar phase size")
    return leaves


def compile_scene(scene, lexicon, weights):
    return [compile_phase(frames, lexicon, weights) for frames in scene["stages"]]


def freeze(scene_id, phases, use_mini):
    index = int(use_mini)
    return json.dumps([1, scene_id, [[(sentence, scores[index]) for sentence, scores in sorted(phase.items())]
                                     for phase in phases]], ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def rewrite_groups(scene, lexicon):
    for rule in scene["rewrite"]:
        facts, = rule["facts"]
        for pattern in rule["form"]:
            fields = [field for _, field, _, _ in Formatter().parse(pattern) if field is not None]
            if fields != [facts]:
                raise ValueError("each surface must preserve the same fact exactly once")
        for word in rule["facts"][facts]:
            if word in lexicon:
                yield [pattern.format(**{facts: word}) for pattern in rule["form"]]


class GrammarCarrier:
    def __init__(self, resource):
        version, self.scene, phases = json.loads(resource)
        if version != 1 or len(phases) != 3:
            raise ValueError("invalid frozen grammar resource")
        self.digest = sha256(resource).hexdigest()
        self.codebooks = [WordCodec({text.encode(): weight for text, weight in table}) for table in phases]
        self.codes = [{text.decode(): (int(code[::-1], 2), len(code)) for text, code in book.codes.items()}
                      for book in self.codebooks]
        self.examples = [max(table, key=lambda entry: (entry[1], entry[0]))[0] + STOP for table in phases]

    def require_sentence(self, phase, text):
        if not text.endswith(STOP) or text[:-1] not in self.codes[phase]:
            raise ValueError("sentence is outside its grammar phase")

    def render(self, packet):
        if not 1 <= len(packet) <= MAX_ENVELOPE_BYTES:
            raise ValueError("invalid synthetic envelope size")
        number = int.from_bytes(b"\x01" + packet, "big")
        sentences = []
        length, units = 0, 0
        phase = 0
        while number:
            nodes = self.codebooks[phase].nodes
            entry = nodes[-1]
            while not isinstance(entry, bytes):
                entry = nodes[entry[number & 1]]
                number >>= 1
            length += len(entry) + len(STOP.encode())
            text = entry.decode() + STOP
            units += len(text)
            if length > CARRIER_BYTES or units > CARRIER_UNITS:
                raise CapacityError("synthetic envelope exceeds the grammar carrier limit")
            sentences.append(text)
            phase = (phase + 1) % len(self.codebooks)
        return "".join(sentences)

    def recover(self, text):
        if not 1 <= len(text) <= CARRIER_UNITS or not text.endswith(STOP) or len(text.encode()) > CARRIER_BYTES:
            raise ValueError("invalid grammar carrier bounds or punctuation")
        number, width = 0, 0
        for index, sentence in enumerate(text[:-1].split(STOP)):
            code = self.codes[index % len(self.codes)].get(sentence)
            if code is None:
                raise ValueError("unknown or out-of-order grammar sentence")
            bits, length = code
            number |= bits << width
            width += length
        framed = number.to_bytes((number.bit_length() + 7) // 8, "big")
        if not 2 <= len(framed) <= MAX_ENVELOPE_BYTES + 1 or framed[0] != 1:
            raise ValueError("invalid synthetic grammar frame")
        packet = framed[1:]
        if self.render(packet) != text:
            raise ValueError("noncanonical grammar carrier")
        return packet
