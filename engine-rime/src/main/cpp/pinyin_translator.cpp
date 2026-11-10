#include <rime/algo/spelling.h>
#include <rime/algo/syllabifier.h>
#include <rime/config.h>
#include <rime/context.h>
#include <rime/dict/corrector.h>
#include <rime/dict/dictionary.h>
#include <rime/dict/prism.h>
#include <rime/engine.h>
#include <rime/gear/poet.h>
#include <rime/gear/script_translator.h>
#include <rime/registry.h>
#include <rime/schema.h>
#include <rime/segmentation.h>

#include <algorithm>
#include <cmath>

namespace {
using namespace rime;

constexpr size_t kRankingWindow = 16;
constexpr size_t kMaxSplitInput = 24;
constexpr size_t kMaxSplitSyllables = 8;
constexpr size_t kMaxSplitAttempts = 2;
constexpr int kSplitSearchBudget = 96;

bool IsPinyin(const string& input, bool allow_delimiter) {
  return !input.empty() && std::all_of(input.begin(), input.end(), [=](char ch) {
    return (ch >= 'a' && ch <= 'z') || (allow_delimiter && ch == '\'');
  });
}

string WithDelimiter(string spelling, char delimiter) {
  std::replace_if(spelling.begin(), spelling.end(),
                  [](char ch) { return ch == ' ' || ch == '\''; }, delimiter);
  return spelling;
}

bool CoversInput(const Candidate& candidate, const Segment& segment) {
  return candidate.start() == segment.start && candidate.end() == segment.end &&
         candidate.type() != "completion";
}

bool ExactSpelling(const Candidate& candidate, const Segment& segment) {
  if (!CoversInput(candidate, segment)) return false;
  auto typed = WithDelimiter(candidate.preedit(), ' ');
  while (!typed.empty() && typed.back() == ' ') typed.pop_back();
  return !typed.empty() && typed == candidate.comment();
}

// Only the fallback phrase needs projected offsets. The main translation
// continues to use librime's own syllable graph and candidate objects.
class SplitSpans : public PhraseSyllabifier {
 public:
  explicit SplitSpans(Spans spans) : spans_(std::move(spans)) {}
  Spans Syllabify(const Phrase*) override { return spans_; }

 private:
  Spans spans_;
};

bool AddSyllable(SyllableGraph* graph, Prism& prism, const string& spelling,
                 size_t start, size_t end, SpellingType type) {
  SyllableId key;
  if (!prism.GetValue(spelling, &key)) return false;
  auto& edges = graph->edges[start][end];
  for (auto mapping = prism.QuerySpelling(key); !mapping.exhausted(); mapping.Next()) {
    if (mapping.properties().type != type) continue;
    EdgeProperties properties(mapping.properties());
    properties.end_pos = end;
    auto& edge = edges.emplace(mapping.syllable_id(), properties).first->second;
    graph->indices[start][mapping.syllable_id()].push_back(&edge);
  }
  return !edges.empty();
}

class SplitSearch {
 public:
  SplitSearch(Prism& prism, const string& input, string current, bool allow_initials)
      : prism_(prism), input_(input), current_(std::move(current)),
        allow_initials_(allow_initials) {}

  vector<string> Run() {
    Find(0, 0, "");
    return std::move(splits_);
  }

 private:
  void Find(size_t offset, size_t syllables, const string& spelling) {
    if (remaining_-- <= 0 || splits_.size() == kMaxSplitAttempts) return;
    if (offset == input_.size()) {
      if (syllables > 1 && spelling != current_) splits_.push_back(spelling);
      return;
    }
    if (syllables == kMaxSplitSyllables) return;
    vector<Prism::Match> matches;
    prism_.CommonPrefixSearch(input_.substr(offset), &matches);
    // Longest syllable first, consistent with the ordinary spelling preference.
    for (auto match = matches.rbegin(); match != matches.rend() && remaining_ > 0 &&
         splits_.size() < kMaxSplitAttempts; ++match) {
      if (match->length == 0 || match->length > 6 || !Allowed(match->value)) continue;
      const auto part = input_.substr(offset, match->length);
      Find(offset + match->length, syllables + 1, spelling.empty() ? part : spelling + "'" + part);
    }
  }

  bool Allowed(SyllableId spelling_id) {
    for (auto mapping = prism_.QuerySpelling(spelling_id); !mapping.exhausted(); mapping.Next()) {
      const auto type = mapping.properties().type;
      if (type == kNormalSpelling || (allow_initials_ && type == kAbbreviation)) return true;
    }
    return false;
  }

  Prism& prism_;
  const string& input_;
  string current_;
  bool allow_initials_;
  int remaining_ = kSplitSearchBudget;
  vector<string> splits_;
};

class PinyinTranslator : public ScriptTranslator {
 public:
  explicit PinyinTranslator(const Ticket& ticket) : ScriptTranslator(ticket) {
    const auto& id = ticket.engine->schema()->schema_id();
    auto* config = ticket.engine->schema()->config();
    full_pinyin_ = id == "zeroinput_pinyin";
    if (id.rfind("zeroinput_pinyin_", 0) == 0) {
      config->GetBool("zeroinput/full_pinyin", &full_pinyin_);
      config->GetBool("zeroinput/prefer_exact_spelling", &prefer_exact_);
    }
  }

  an<Translation> Query(const string& input, const Segment& segment) override {
    auto tail = ScriptTranslator::Query(input, segment);
    if (!full_pinyin_ || !segment.HasTag("abc") || !IsPinyin(input, true)) return tail;
    an<Candidate> alternate;
    CandidateList mixed;
    if (!prefer_exact_) {
      auto first = tail && !tail->exhausted() ? tail->Peek() : nullptr;
      mixed = MixedInitialPhrases(input, segment, first);
      if (mixed.empty()) alternate = Alternative(input, segment, first);
      if (!alternate && mixed.empty()) return tail;
    }

    CandidateList head;
    const size_t limit = prefer_exact_ ? kRankingWindow : 1;
    while (tail && !tail->exhausted() && head.size() < limit) {
      auto candidate = tail->Peek();
      if (!candidate) break;
      head.push_back(candidate);
      tail->Next();
    }
    if (prefer_exact_) {
      std::stable_partition(head.begin(), head.end(), [&](const an<Candidate>& candidate) {
        return ExactSpelling(*candidate, segment);
      });
      auto first = head.empty() ? nullptr : head.front();
      mixed = MixedInitialPhrases(input, segment, first);
      if (mixed.empty()) alternate = Alternative(input, segment, first);
    }
    if (alternate) {
      const bool keep_first = !head.empty() && CoversInput(*head.front(), segment) &&
          (!ExactSpelling(*alternate, segment) || ExactSpelling(*head.front(), segment));
      head.insert(head.begin() + (keep_first ? 1 : 0), alternate);
    }
    auto prefix = New<FifoTranslation>();
    for (const auto& candidate : mixed) prefix->Append(candidate);
    for (const auto& candidate : head) prefix->Append(candidate);
    return prefix + tail;
  }

 private:
  CandidateList MixedInitialPhrases(const string& input, const Segment& segment,
                                    const an<Candidate>& first) {
    // Literal interjections (n/m) can prune the abbreviated edges for the
    // same first key. Restore only the two-syllable dictionary lookup when
    // that interpretation did not yield a whole dictionary word.
    if (input.size() < 3 || input.size() > 7 || (input[0] != 'n' && input[0] != 'm') ||
        segment.start != 0 || segment.end != engine_->context()->input().size() ||
        !IsPinyin(input, false) || (first && CoversInput(*first, segment) && first->type() != "sentence") ||
        !dict() || !dict()->loaded()) return {};

    auto& prism = *dict()->prism();
    const auto initial = input.substr(0, 1);
    const auto typed = input.substr(1);
    vector<string> syllables{typed};
    // A bounded eng/en recovery for a mixed code such as nmeng. A successful
    // lookup of the literal spelling always takes precedence.
    if (typed.size() > 3 && typed.compare(typed.size() - 3, 3, "eng") == 0) {
      syllables.push_back(typed.substr(0, typed.size() - 1));
    }
    CandidateList candidates;
    for (const auto& syllable : syllables) {
      auto entry = LookupMixedPhrase(prism, initial, syllable, input.size());
      if (!entry || (first && entry->text == first->text()) ||
          (!candidates.empty() && entry->text == candidates.front()->text())) continue;
      entry->comment = Spell(entry->code);
      auto phrase = New<Phrase>(language(), "phrase", 0, segment.end, entry);
      phrase->set_quality(std::exp(entry->weight) + initial_quality() - 1);
      candidates.push_back(Project(*phrase, initial + "'" + typed, segment.end));
    }
    return candidates;
  }

  an<DictEntry> LookupMixedPhrase(Prism& prism, const string& initial,
                                 const string& syllable, size_t end) {
    SyllableGraph graph;
    graph.input_length = graph.interpreted_length = end;
    if (!AddSyllable(&graph, prism, initial, 0, 1, kAbbreviation) ||
        !AddSyllable(&graph, prism, syllable, 1, end, kNormalSpelling)) return nullptr;
    auto entries = dict()->Lookup(graph, 0);
    if (!entries) return nullptr;
    auto whole = entries->find(end);
    return whole == entries->end() ? nullptr : whole->second.Peek();
  }

  an<Candidate> Alternative(const string& input, const Segment& segment, const an<Candidate>& first) {
    auto* dictionary = dict();
    if (input.size() < 2 || input.size() > kMaxSplitInput || segment.start != 0 ||
        segment.end != engine_->context()->input().size() || !IsPinyin(input, false) ||
        !dictionary || !dictionary->loaded()) return nullptr;
    const bool complete = first && CoversInput(*first, segment);
    // Codes such as "ng" can be a literal syllable or several initials.
    const bool initial_collision = complete && input.size() <= 3 &&
        input.find_first_of("aeiouv") == string::npos &&
        first->preedit() == input && first->comment() == input;
    if (complete && first->type() != "sentence" &&
        !initial_collision && (!prefer_exact_ || ExactSpelling(*first, segment))) return nullptr;
    const auto current = first ? WithDelimiter(first->preedit(), '\'') : "";
    const bool allow_initials = !complete || initial_collision;
    for (const auto& spelling : SplitSearch(*dictionary->prism(), input, current, allow_initials).Run()) {
      Segment split(0, static_cast<int>(spelling.size()));
      split.tags = segment.tags;
      auto translation = ScriptTranslator::Query(spelling, split);
      for (int i = 0; i < 4 && translation && !translation->exhausted(); ++i, translation->Next()) {
        auto phrase = As<Phrase>(translation->Peek());
        if (!phrase || !CoversInput(*phrase, split) || WithDelimiter(phrase->preedit(), '\'') != spelling ||
            (!allow_initials && !ExactSpelling(*phrase, split)) || (first && phrase->text() == first->text())) continue;
        return Project(*phrase, spelling, segment.end);
      }
    }
    return nullptr;
  }

  an<Candidate> Project(const Phrase& phrase, const string& spelling, size_t end) {
    auto entry = New<DictEntry>(phrase.entry());
    entry->preedit = WithDelimiter(spelling, ' ');
    auto projected = New<Phrase>(language(), phrase.type(), 0, end, entry);
    Spans spans;
    size_t offset = 0;
    spans.AddVertex(offset);
    for (char ch : spelling) {
      if (ch == '\'') spans.AddVertex(offset);
      else ++offset;
    }
    spans.AddVertex(end);
    projected->set_syllabifier(New<SplitSpans>(std::move(spans)));
    projected->set_quality(phrase.quality());
    return projected;
  }

  bool full_pinyin_ = false;
  bool prefer_exact_ = false;
};
}  // namespace

void ZeroInputInstallPinyinTranslator() {
  // This private librime runtime only deploys ZeroInput's reviewed schemas.
  // Keep the compiled default schema and prism usable without redeploying them.
  rime::Registry::instance().Register("script_translator", new rime::Component<PinyinTranslator>());
}
