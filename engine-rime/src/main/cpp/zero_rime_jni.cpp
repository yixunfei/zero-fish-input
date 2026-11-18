#include <jni.h>
#include <rime_api.h>
#include <rime/candidate.h>
#include <rime/context.h>
#include <rime/service.h>
#include <opencc/Config.hpp>
#include <opencc/Converter.hpp>

#include <algorithm>
#include <cstdint>
#include <mutex>
#include <new>
#include "jni_boundary.h"
#include "jni_strings.h"
#include <string>
#include <vector>

bool ZeroInputLoadGrammar(const std::string& shared_directory);
void ZeroInputInstallPinyinTranslator();

namespace {
std::mutex engine_mutex;
RimeApi* api = nullptr;
bool initialized = false;
std::string shared_directory;
std::string user_directory;
opencc::ConverterPtr simplified_converter;
opencc::ConverterPtr traditional_converter;

using zero_jni::ToUtf8;
using zero_jni::ToJString;
using zero_jni::ToStringArray;

std::string ConvertDisplayText(JNIEnv* env, const std::string& value,
                               bool simplified) {
  if (value.empty()) return value;
  const auto& converter = simplified ? simplified_converter : traditional_converter;
  if (!converter) {
    zero_jni::Throw(env, "java/lang/IllegalStateException", "Text conversion is unavailable");
    return {};
  }
  try {
    return converter->Convert(value);
  } catch (...) {
    zero_jni::Throw(env, "java/lang/IllegalStateException", "Text conversion failed");
    return {};
  }
}

struct ContextLease {
  RimeContext value{};
  bool acquired;
  explicit ContextLease(RimeSessionId id) {
    RIME_STRUCT_INIT(RimeContext, value);
    acquired = api && id && api->get_context(id, &value);
  }
  ~ContextLease() noexcept { if (acquired) { try { api->free_context(&value); } catch (...) {} } }
};

struct CommitLease {
  RimeCommit value{};
  bool acquired;
  explicit CommitLease(RimeSessionId id) {
    RIME_STRUCT_INIT(RimeCommit, value);
    acquired = api && id && api->get_commit(id, &value);
  }
  ~CommitLease() noexcept { if (acquired) { try { api->free_commit(&value); } catch (...) {} } }
};
}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_zeroinput_engine_rime_NativeRimeBridge_nativeInitialize(
    JNIEnv* env, jobject, jstring shared_dir, jstring user_dir) try {
  std::lock_guard<std::mutex> lock(engine_mutex);
  if (initialized) return JNI_TRUE;
  shared_directory = ToUtf8(env, shared_dir);
  if (env->ExceptionCheck()) return JNI_FALSE;
  user_directory = ToUtf8(env, user_dir);
  if (env->ExceptionCheck()) return JNI_FALSE;
  if (shared_directory.empty() || user_directory.empty()) return JNI_FALSE;

  try {
    opencc::Config config;
    simplified_converter = config.NewFromFile(shared_directory + "/opencc/t2s.json");
    traditional_converter = config.NewFromFile(shared_directory + "/opencc/s2t.json");
  } catch (...) {
    simplified_converter.reset();
    traditional_converter.reset();
    return JNI_FALSE;
  }

  api = rime_get_api();
  if (!api) return JNI_FALSE;
  RimeTraits traits{};
  RIME_STRUCT_INIT(RimeTraits, traits);
  traits.shared_data_dir = shared_directory.c_str();
  traits.user_data_dir = user_directory.c_str();
  traits.distribution_name = "ZeroInput";
  traits.distribution_code_name = "zeroinput";
  traits.distribution_version = "0.1.0";
  traits.app_name = "rime.zeroinput";
  traits.min_log_level = 3;
  traits.log_dir = "";
  const char* modules[] = {"default", "octagram", nullptr};
  traits.modules = modules;
  struct InitializationLease {
    bool complete = false;
    ~InitializationLease() noexcept {
      if (complete) return;
      if (api) { try { api->finalize(); } catch (...) {} }
      initialized = false;
      api = nullptr;
      simplified_converter.reset();
      traditional_converter.reset();
    }
  } lease;
  api->setup(&traits);
  api->initialize(&traits);
  if (!ZeroInputLoadGrammar(shared_directory)) {
    return JNI_FALSE;
  }
  // A successful setup/initialize is not enough to use a schema: librime
  // must also complete its maintenance pass.  Treat a failed maintenance
  // start as initialization failure and tear down the partially initialized
  // API so Kotlin can expose a terminal FAILED state and use its fallback
  // engine instead of reporting a permanently "initializing" runtime.
  if (!api->start_maintenance(true)) {
    shared_directory.clear();
    user_directory.clear();
    return JNI_FALSE;
  }
  api->join_maintenance_thread();
  ZeroInputInstallPinyinTranslator();
  initialized = true;
  lease.complete = true;
  return JNI_TRUE;
} catch (...) {
  zero_jni::TranslateException(env);
  return JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_zeroinput_engine_rime_NativeRimeBridge_nativeFinalize(
    JNIEnv* env, jobject) try {
  std::lock_guard<std::mutex> lock(engine_mutex);
  // Drop published runtime state even if upstream teardown fails.
  struct FinalizationLease {
    ~FinalizationLease() noexcept {
      initialized = false;
      api = nullptr;
      simplified_converter.reset();
      traditional_converter.reset();
    }
  } lease;
  if (initialized && api) {
    api->cleanup_all_sessions();
    api->finalize();
  }
} catch (...) {
  zero_jni::TranslateException(env);
  return;
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_zeroinput_engine_rime_NativeRimeBridge_nativeCreateSession(
    JNIEnv* env, jobject, jstring schema_id) try {
  std::lock_guard<std::mutex> lock(engine_mutex);
  if (!initialized || !api) return 0;
  const std::string schema = ToUtf8(env, schema_id);
  if (env->ExceptionCheck()) return 0;
  if (schema != "zeroinput_pinyin" && schema.rfind("zeroinput_pinyin_", 0) != 0) return 0;
  if (schema.size() > 64 || !std::all_of(schema.begin(), schema.end(), [](char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_';
      })) return 0;
  const RimeSessionId session = api->create_session();
  struct SessionLease {
    RimeSessionId id;
    ~SessionLease() noexcept { if (id) { try { api->destroy_session(id); } catch (...) {} } }
  } lease{session};
  if (session && !api->select_schema(session, schema.c_str())) {
    // Never continue with librime's implicit/default schema: it may enable
    // user-dictionary learning or otherwise change the privacy contract.
    return 0;
  }
  lease.id = 0;
  return static_cast<jlong>(session);
} catch (...) {
  zero_jni::TranslateException(env);
  return 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_zeroinput_engine_rime_NativeRimeBridge_nativeDeploySchema(
    JNIEnv* env, jobject, jstring path) try {
  std::lock_guard<std::mutex> lock(engine_mutex);
  const std::string file = ToUtf8(env, path);
  if (env->ExceptionCheck()) return JNI_FALSE;
  const std::string prefix = user_directory + "/zeroinput_pinyin_";
  const std::string suffix = ".schema.yaml";
  if (file.rfind(prefix, 0) != 0 || file.size() <= prefix.size() + suffix.size() ||
      file.size() > prefix.size() + 64 || file.substr(file.size() - suffix.size()) != suffix) return JNI_FALSE;
  const std::string variant = file.substr(prefix.size(), file.size() - prefix.size() - suffix.size());
  if (!std::all_of(variant.begin(), variant.end(), [](char ch) {
        return (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'z') || ch == '_';
      })) return JNI_FALSE;
  return initialized && api && api->deploy_schema(file.c_str()) ? JNI_TRUE : JNI_FALSE;
} catch (...) {
  zero_jni::TranslateException(env);
  return JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_zeroinput_engine_rime_NativeRimeBridge_nativeSetOptions(
    JNIEnv* env, jobject, jlong session_id, jboolean simplified, jboolean ascii_punctuation) try {
  std::lock_guard<std::mutex> lock(engine_mutex);
  if (!api || !session_id) return;
  api->set_option(session_id, "simplification", simplified);
  api->set_option(session_id, "ascii_punct", ascii_punctuation);
} catch (...) {
  zero_jni::TranslateException(env);
  return;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_zeroinput_engine_rime_NativeRimeBridge_nativeConvertText(
    JNIEnv* env, jobject, jstring text, jboolean simplified) try {
  std::lock_guard<std::mutex> lock(engine_mutex);
  const auto source = ToUtf8(env, text);
  if (env->ExceptionCheck()) return nullptr;
  const auto converted = ConvertDisplayText(env, source, simplified);
  return env->ExceptionCheck() ? nullptr : ToJString(env, converted.c_str());
} catch (...) {
  zero_jni::TranslateException(env);
  return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_zeroinput_engine_rime_NativeRimeBridge_nativeDestroySession(
    JNIEnv* env, jobject, jlong session_id) try {
  std::lock_guard<std::mutex> lock(engine_mutex);
  if (api && session_id) api->destroy_session(static_cast<RimeSessionId>(session_id));
} catch (...) {
  zero_jni::TranslateException(env);
  return;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_zeroinput_engine_rime_NativeRimeBridge_nativeProcessKey(
    JNIEnv* env, jobject, jlong session_id, jint key_code, jint modifiers) try {
  std::lock_guard<std::mutex> lock(engine_mutex);
  return api && api->process_key(static_cast<RimeSessionId>(session_id), key_code,
                                 modifiers)
             ? JNI_TRUE
             : JNI_FALSE;
} catch (...) {
  zero_jni::TranslateException(env);
  return JNI_FALSE;
}

extern "C" JNIEXPORT jobject JNICALL
Java_dev_zeroinput_engine_rime_NativeRimeBridge_nativeReadUpdate(
    JNIEnv* env, jobject, jlong session_id, jboolean simplified, jboolean include_spelling) try {
  std::lock_guard<std::mutex> lock(engine_mutex);
  ContextLease context(session_id);
  CommitLease commit(session_id);
  std::vector<std::string> texts;
  std::vector<std::string> comments;
  std::vector<std::string> spellings;
  std::vector<jint> spelling_ranges;
  if (context.acquired) {
    const auto& menu = context.value.menu;
    const int count = std::min(menu.num_candidates, 10);
    auto session = include_spelling ? rime::Service::instance().GetSession(session_id) : nullptr;
    const auto* current = session ? session->context() : nullptr;
    const auto* segment = current && !current->composition().empty() ? &current->composition().back() : nullptr;
    for (int index = 0; index < count; ++index) {
      const auto& candidate = menu.candidates[index];
      texts.emplace_back(ConvertDisplayText(
          env, candidate.text ? candidate.text : "", simplified));
      if (env->ExceptionCheck()) return nullptr;
      comments.emplace_back(candidate.comment ? candidate.comment : "");
      if (include_spelling) {
        const auto absolute_index = static_cast<size_t>(menu.page_no) * menu.page_size + index;
        auto row = segment ? segment->GetCandidateAt(absolute_index) : nullptr;
        spellings.emplace_back(row ? row->preedit() : "");
        spelling_ranges.push_back(row ? static_cast<jint>(row->start()) : -1);
        spelling_ranges.push_back(row ? static_cast<jint>(row->end()) : -1);
      }
    }
  }
  jstring raw = ToJString(env, api ? api->get_input(session_id) : "");
  if (!raw || env->ExceptionCheck()) return nullptr;
  jstring composition = ToJString(env, context.acquired ? context.value.composition.preedit : "");
  if (!composition || env->ExceptionCheck()) return nullptr;
  const auto committed_text = ConvertDisplayText(
      env, commit.acquired ? commit.value.text : "", simplified);
  if (env->ExceptionCheck()) return nullptr;
  jstring committed = ToJString(env, committed_text.c_str());
  if (!committed || env->ExceptionCheck()) return nullptr;
  jobjectArray candidates = ToStringArray(env, texts);
  if (!candidates || env->ExceptionCheck()) return nullptr;
  jobjectArray candidate_comments = ToStringArray(env, comments);
  if (!candidate_comments || env->ExceptionCheck()) return nullptr;
  jobjectArray candidate_spellings = ToStringArray(env, spellings);
  if (!candidate_spellings || env->ExceptionCheck()) return nullptr;
  const auto range_count = static_cast<jsize>(spelling_ranges.size());
  jintArray candidate_ranges = env->NewIntArray(range_count);
  if (!candidate_ranges || env->ExceptionCheck()) return nullptr;
  if (range_count != 0) env->SetIntArrayRegion(candidate_ranges, 0, range_count, spelling_ranges.data());
  if (env->ExceptionCheck()) return nullptr;
  jclass type = env->FindClass("dev/zeroinput/engine/rime/NativeRimeUpdate");
  if (!type || env->ExceptionCheck()) return nullptr;
  jmethodID constructor = env->GetMethodID(type, "<init>",
      "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;"
      "[Ljava/lang/String;IZII[Ljava/lang/String;[I)V");
  if (!constructor) return nullptr;
  jobject result = env->NewObject(type, constructor, raw, composition, committed, candidates,
      candidate_comments, context.acquired ? context.value.menu.page_no : 0,
      static_cast<jboolean>(!context.acquired || context.value.menu.is_last_page),
      context.acquired ? context.value.menu.highlighted_candidate_index : 0,
      api && session_id ? static_cast<jint>(api->get_caret_pos(session_id)) : 0,
      candidate_spellings, candidate_ranges);
  env->DeleteLocalRef(raw);
  env->DeleteLocalRef(composition);
  env->DeleteLocalRef(committed);
  env->DeleteLocalRef(candidates);
  env->DeleteLocalRef(candidate_comments);
  env->DeleteLocalRef(candidate_spellings);
  env->DeleteLocalRef(candidate_ranges);
  env->DeleteLocalRef(type);
  return result;
} catch (...) {
  zero_jni::TranslateException(env);
  return nullptr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_zeroinput_engine_rime_NativeRimeBridge_nativeSelectCandidate(
    JNIEnv* env, jobject, jlong session_id, jint index) try {
  std::lock_guard<std::mutex> lock(engine_mutex);
  return api && index >= 0 && api->select_candidate_on_current_page(
                                   static_cast<RimeSessionId>(session_id), index)
             ? JNI_TRUE
             : JNI_FALSE;
} catch (...) {
  zero_jni::TranslateException(env);
  return JNI_FALSE;
}

extern "C" JNIEXPORT jobject JNICALL
Java_dev_zeroinput_engine_rime_NativeRimeBridge_nativeCandidatePage(
    JNIEnv* env, jobject, jlong session_id, jint page, jint size,
    jboolean simplified) try {
  std::lock_guard<std::mutex> lock(engine_mutex);
  std::vector<std::string> texts;
  std::vector<std::string> comments;
  bool has_next = false;
  if (api && session_id && page >= 0 && page <= 1000000 && size >= 1 && size <= 10) {
    RimeCandidateListIterator iterator{};
    if (api->candidate_list_from_index(session_id, &iterator, page * size)) {
      struct IteratorLease {
        RimeCandidateListIterator* value;
        ~IteratorLease() noexcept { try { api->candidate_list_end(value); } catch (...) {} }
      } lease{&iterator};
      while (api->candidate_list_next(&iterator)) {
        if (texts.size() == static_cast<size_t>(size)) { has_next = true; break; }
        texts.emplace_back(ConvertDisplayText(
            env, iterator.candidate.text ? iterator.candidate.text : "", simplified));
        if (env->ExceptionCheck()) {
          return nullptr;
        }
        comments.emplace_back(iterator.candidate.comment ? iterator.candidate.comment : "");
      }
    }
  }
  jclass type = env->FindClass("dev/zeroinput/engine/rime/NativeCandidatePage");
  if (!type || env->ExceptionCheck()) return nullptr;
  jmethodID constructor = env->GetMethodID(type, "<init>", "([Ljava/lang/String;[Ljava/lang/String;Z)V");
  if (!constructor) return nullptr;
  jobjectArray values = ToStringArray(env, texts);
  if (!values || env->ExceptionCheck()) return nullptr;
  jobjectArray hints = ToStringArray(env, comments);
  if (!hints || env->ExceptionCheck()) return nullptr;
  jobject result = env->NewObject(type, constructor, values, hints, static_cast<jboolean>(has_next));
  env->DeleteLocalRef(values);
  env->DeleteLocalRef(hints);
  env->DeleteLocalRef(type);
  return result;
} catch (...) {
  zero_jni::TranslateException(env);
  return nullptr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_zeroinput_engine_rime_NativeRimeBridge_nativeSelectAbsoluteCandidate(
    JNIEnv* env, jobject, jlong session_id, jint index) try {
  std::lock_guard<std::mutex> lock(engine_mutex);
  return api && session_id && index >= 0 && api->select_candidate(
      static_cast<RimeSessionId>(session_id), static_cast<size_t>(index)) ? JNI_TRUE : JNI_FALSE;
} catch (...) {
  zero_jni::TranslateException(env);
  return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_zeroinput_engine_rime_NativeRimeBridge_nativeChangePage(
    JNIEnv* env, jobject, jlong session_id, jboolean backwards) try {
  std::lock_guard<std::mutex> lock(engine_mutex);
  return api && api->change_page(static_cast<RimeSessionId>(session_id), backwards)
             ? JNI_TRUE
             : JNI_FALSE;
} catch (...) {
  zero_jni::TranslateException(env);
  return JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_zeroinput_engine_rime_NativeRimeBridge_nativeClearComposition(
    JNIEnv* env, jobject, jlong session_id) try {
  std::lock_guard<std::mutex> lock(engine_mutex);
  if (api) api->clear_composition(static_cast<RimeSessionId>(session_id));
} catch (...) {
  zero_jni::TranslateException(env);
  return;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_zeroinput_engine_rime_NativeRimeBridge_nativeVersion(
    JNIEnv* env, jobject) try {
  std::lock_guard<std::mutex> lock(engine_mutex);
  return ToJString(env, api ? api->get_version() : "");
} catch (...) {
  zero_jni::TranslateException(env);
  return nullptr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_zeroinput_engine_rime_NativeRimeBridge_nativeSetInput(
    JNIEnv* env, jobject, jlong session_id, jstring input) try {
  const auto value = ToUtf8(env, input);
  if (env->ExceptionCheck()) return JNI_FALSE;
  if (value.size() > 128 || !std::all_of(value.begin(), value.end(), [](char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= '2' && ch <= '9') || ch == '\'' || ch == ';';
      })) return JNI_FALSE;
  std::lock_guard<std::mutex> lock(engine_mutex);
  return api && session_id && api->set_input(session_id, value.c_str()) ? JNI_TRUE : JNI_FALSE;
} catch (...) {
  zero_jni::TranslateException(env);
  return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_zeroinput_engine_rime_NativeRimeBridge_nativeSetCaret(
    JNIEnv* env, jobject, jlong session_id, jint position) try {
  std::lock_guard<std::mutex> lock(engine_mutex);
  if (!api || !session_id || position < 0 || position > 128) return JNI_FALSE;
  const char* input = api->get_input(session_id);
  if (!input || static_cast<size_t>(position) > std::char_traits<char>::length(input)) return JNI_FALSE;
  api->set_caret_pos(session_id, static_cast<size_t>(position));
  return JNI_TRUE;
} catch (...) {
  zero_jni::TranslateException(env);
  return JNI_FALSE;
}
