#include <jni.h>
#include <rime_api.h>
#include <opencc/Config.hpp>
#include <opencc/Converter.hpp>

#include <algorithm>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

namespace {
std::mutex engine_mutex;
RimeApi* api = nullptr;
bool initialized = false;
std::string shared_directory;
std::string user_directory;
opencc::ConverterPtr simplified_converter;
opencc::ConverterPtr traditional_converter;

void AppendUtf8(std::string& output, std::uint32_t code_point) {
  if (code_point <= 0x7f) {
    output.push_back(static_cast<char>(code_point));
  } else if (code_point <= 0x7ff) {
    output.push_back(static_cast<char>(0xc0 | (code_point >> 6)));
    output.push_back(static_cast<char>(0x80 | (code_point & 0x3f)));
  } else if (code_point <= 0xffff) {
    output.push_back(static_cast<char>(0xe0 | (code_point >> 12)));
    output.push_back(static_cast<char>(0x80 | ((code_point >> 6) & 0x3f)));
    output.push_back(static_cast<char>(0x80 | (code_point & 0x3f)));
  } else {
    output.push_back(static_cast<char>(0xf0 | (code_point >> 18)));
    output.push_back(static_cast<char>(0x80 | ((code_point >> 12) & 0x3f)));
    output.push_back(static_cast<char>(0x80 | ((code_point >> 6) & 0x3f)));
    output.push_back(static_cast<char>(0x80 | (code_point & 0x3f)));
  }
}

std::string ToUtf8(JNIEnv* env, jstring value) {
  if (!value) return {};
  const jchar* chars = env->GetStringChars(value, nullptr);
  if (!chars) return {};
  const jsize length = env->GetStringLength(value);
  std::string output;
  output.reserve(static_cast<std::size_t>(length) * 3);
  for (jsize index = 0; index < length; ++index) {
    std::uint32_t code_point = chars[index];
    if (code_point >= 0xd800 && code_point <= 0xdbff && index + 1 < length) {
      const std::uint32_t low = chars[index + 1];
      if (low >= 0xdc00 && low <= 0xdfff) {
        code_point = 0x10000 + ((code_point - 0xd800) << 10) + (low - 0xdc00);
        ++index;
      }
    }
    AppendUtf8(output, code_point);
  }
  env->ReleaseStringChars(value, chars);
  return output;
}

std::uint32_t DecodeUtf8CodePoint(const unsigned char*& cursor,
                                  const unsigned char* end) {
  const unsigned char first = *cursor++;
  if (first < 0x80) return first;
  int continuation_count = first < 0xe0 ? 1 : (first < 0xf0 ? 2 : 3);
  std::uint32_t value = first & (0x7f >> continuation_count);
  if (cursor + continuation_count > end) return 0xfffd;
  for (int index = 0; index < continuation_count; ++index) {
    const unsigned char next = *cursor++;
    if ((next & 0xc0) != 0x80) return 0xfffd;
    value = (value << 6) | (next & 0x3f);
  }
  return value <= 0x10ffff ? value : 0xfffd;
}

jstring ToJString(JNIEnv* env, const char* value) {
  if (!value || !*value) return env->NewString(nullptr, 0);
  const auto* cursor = reinterpret_cast<const unsigned char*>(value);
  const auto* end = cursor + std::char_traits<char>::length(value);
  std::vector<jchar> output;
  while (cursor < end) {
    const std::uint32_t code_point = DecodeUtf8CodePoint(cursor, end);
    if (code_point <= 0xffff) {
      output.push_back(static_cast<jchar>(code_point));
    } else {
      const std::uint32_t adjusted = code_point - 0x10000;
      output.push_back(static_cast<jchar>(0xd800 + (adjusted >> 10)));
      output.push_back(static_cast<jchar>(0xdc00 + (adjusted & 0x3ff)));
    }
  }
  return env->NewString(output.data(), static_cast<jsize>(output.size()));
}

jobjectArray ToStringArray(JNIEnv* env,
                           const std::vector<std::string>& values) {
  jclass string_class = env->FindClass("java/lang/String");
  jobjectArray result =
      env->NewObjectArray(static_cast<jsize>(values.size()), string_class, nullptr);
  for (std::size_t index = 0; index < values.size(); ++index) {
    jstring value = ToJString(env, values[index].c_str());
    env->SetObjectArrayElement(result, static_cast<jsize>(index), value);
    env->DeleteLocalRef(value);
  }
  env->DeleteLocalRef(string_class);
  return result;
}

struct ContextLease {
  RimeContext value{};
  bool acquired;
  explicit ContextLease(RimeSessionId id) {
    RIME_STRUCT_INIT(RimeContext, value);
    acquired = api && id && api->get_context(id, &value);
  }
  ~ContextLease() { if (acquired) api->free_context(&value); }
};

struct CommitLease {
  RimeCommit value{};
  bool acquired;
  explicit CommitLease(RimeSessionId id) {
    RIME_STRUCT_INIT(RimeCommit, value);
    acquired = api && id && api->get_commit(id, &value);
  }
  ~CommitLease() { if (acquired) api->free_commit(&value); }
};
}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_zeroinput_engine_rime_NativeRimeBridge_nativeInitialize(
    JNIEnv* env, jobject, jstring shared_dir, jstring user_dir) {
  std::lock_guard<std::mutex> lock(engine_mutex);
  if (initialized) return JNI_TRUE;
  shared_directory = ToUtf8(env, shared_dir);
  user_directory = ToUtf8(env, user_dir);
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
  api->setup(&traits);
  api->initialize(&traits);
  // A successful setup/initialize is not enough to use a schema: librime
  // must also complete its maintenance pass.  Treat a failed maintenance
  // start as initialization failure and tear down the partially initialized
  // API so Kotlin can expose a terminal FAILED state and use its fallback
  // engine instead of reporting a permanently "initializing" runtime.
  if (!api->start_maintenance(true)) {
    api->finalize();
    api = nullptr;
    shared_directory.clear();
    user_directory.clear();
    return JNI_FALSE;
  }
  api->join_maintenance_thread();
  initialized = true;
  return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_zeroinput_engine_rime_NativeRimeBridge_nativeFinalize(
    JNIEnv*, jobject) {
  std::lock_guard<std::mutex> lock(engine_mutex);
  if (initialized && api) {
    api->cleanup_all_sessions();
    api->finalize();
  }
  initialized = false;
  api = nullptr;
  simplified_converter.reset();
  traditional_converter.reset();
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_zeroinput_engine_rime_NativeRimeBridge_nativeCreateSession(
    JNIEnv* env, jobject, jstring schema_id) {
  std::lock_guard<std::mutex> lock(engine_mutex);
  if (!initialized || !api) return 0;
  const std::string schema = ToUtf8(env, schema_id);
  if (schema != "zeroinput_pinyin" && schema.rfind("zeroinput_pinyin_", 0) != 0) return 0;
  if (schema.size() > 64 || !std::all_of(schema.begin(), schema.end(), [](char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_';
      })) return 0;
  const RimeSessionId session = api->create_session();
  if (session && !api->select_schema(session, schema.c_str())) {
    // Never continue with librime's implicit/default schema: it may enable
    // user-dictionary learning or otherwise change the privacy contract.
    api->destroy_session(session);
    return 0;
  }
  return static_cast<jlong>(session);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_zeroinput_engine_rime_NativeRimeBridge_nativeDeploySchema(
    JNIEnv* env, jobject, jstring path) {
  std::lock_guard<std::mutex> lock(engine_mutex);
  const std::string file = ToUtf8(env, path);
  const std::string prefix = user_directory + "/zeroinput_pinyin_";
  const std::string suffix = ".schema.yaml";
  if (file.rfind(prefix, 0) != 0 || file.size() <= prefix.size() + suffix.size() ||
      file.size() > prefix.size() + 64 || file.substr(file.size() - suffix.size()) != suffix) return JNI_FALSE;
  const std::string variant = file.substr(prefix.size(), file.size() - prefix.size() - suffix.size());
  if (!std::all_of(variant.begin(), variant.end(), [](char ch) {
        return (ch >= '0' && ch <= '9') || (ch >= 'a' && ch <= 'z') || ch == '_';
      })) return JNI_FALSE;
  return initialized && api && api->deploy_schema(file.c_str()) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_zeroinput_engine_rime_NativeRimeBridge_nativeSetOptions(
    JNIEnv*, jobject, jlong session_id, jboolean simplified, jboolean ascii_punctuation) {
  std::lock_guard<std::mutex> lock(engine_mutex);
  if (!api || !session_id) return;
  api->set_option(session_id, "simplification", simplified);
  api->set_option(session_id, "ascii_punct", ascii_punctuation);
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_zeroinput_engine_rime_NativeRimeBridge_nativeConvertText(
    JNIEnv* env, jobject, jstring text, jboolean simplified) {
  std::lock_guard<std::mutex> lock(engine_mutex);
  const auto& converter = simplified ? simplified_converter : traditional_converter;
  if (!converter) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Text conversion is unavailable");
    return nullptr;
  }
  try {
    return ToJString(env, converter->Convert(ToUtf8(env, text)).c_str());
  } catch (...) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Text conversion failed");
    return nullptr;
  }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_zeroinput_engine_rime_NativeRimeBridge_nativeDestroySession(
    JNIEnv*, jobject, jlong session_id) {
  std::lock_guard<std::mutex> lock(engine_mutex);
  if (api && session_id) api->destroy_session(static_cast<RimeSessionId>(session_id));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_zeroinput_engine_rime_NativeRimeBridge_nativeProcessKey(
    JNIEnv*, jobject, jlong session_id, jint key_code, jint modifiers) {
  std::lock_guard<std::mutex> lock(engine_mutex);
  return api && api->process_key(static_cast<RimeSessionId>(session_id), key_code,
                                 modifiers)
             ? JNI_TRUE
             : JNI_FALSE;
}

extern "C" JNIEXPORT jobject JNICALL
Java_dev_zeroinput_engine_rime_NativeRimeBridge_nativeReadUpdate(
    JNIEnv* env, jobject, jlong session_id) {
  std::lock_guard<std::mutex> lock(engine_mutex);
  ContextLease context(session_id);
  CommitLease commit(session_id);
  std::vector<std::string> texts;
  std::vector<std::string> comments;
  if (context.acquired) {
    const auto& menu = context.value.menu;
    const int count = std::min(menu.num_candidates, 10);
    for (int index = 0; index < count; ++index) {
      const auto& candidate = menu.candidates[index];
      texts.emplace_back(candidate.text ? candidate.text : "");
      comments.emplace_back(candidate.comment ? candidate.comment : "");
    }
  }
  jstring raw = ToJString(env, api ? api->get_input(session_id) : "");
  jstring composition = ToJString(env, context.acquired ? context.value.composition.preedit : "");
  jstring committed = ToJString(env, commit.acquired ? commit.value.text : "");
  jobjectArray candidates = ToStringArray(env, texts);
  jobjectArray candidate_comments = ToStringArray(env, comments);
  jclass type = env->FindClass("dev/zeroinput/engine/rime/NativeRimeUpdate");
  if (!type || env->ExceptionCheck()) return nullptr;
  jmethodID constructor = env->GetMethodID(type, "<init>",
      "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;IZII)V");
  if (!constructor) return nullptr;
  jobject result = env->NewObject(type, constructor, raw, composition, committed, candidates,
      candidate_comments, context.acquired ? context.value.menu.page_no : 0,
      static_cast<jboolean>(!context.acquired || context.value.menu.is_last_page),
      context.acquired ? context.value.menu.highlighted_candidate_index : 0,
      api && session_id ? static_cast<jint>(api->get_caret_pos(session_id)) : 0);
  env->DeleteLocalRef(raw);
  env->DeleteLocalRef(composition);
  env->DeleteLocalRef(committed);
  env->DeleteLocalRef(candidates);
  env->DeleteLocalRef(candidate_comments);
  env->DeleteLocalRef(type);
  return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_zeroinput_engine_rime_NativeRimeBridge_nativeSelectCandidate(
    JNIEnv*, jobject, jlong session_id, jint index) {
  std::lock_guard<std::mutex> lock(engine_mutex);
  return api && index >= 0 && api->select_candidate_on_current_page(
                                   static_cast<RimeSessionId>(session_id), index)
             ? JNI_TRUE
             : JNI_FALSE;
}

extern "C" JNIEXPORT jobject JNICALL
Java_dev_zeroinput_engine_rime_NativeRimeBridge_nativeCandidatePage(
    JNIEnv* env, jobject, jlong session_id, jint page, jint size) {
  std::lock_guard<std::mutex> lock(engine_mutex);
  std::vector<std::string> texts;
  std::vector<std::string> comments;
  bool has_next = false;
  if (api && session_id && page >= 0 && page <= 1000000 && size >= 1 && size <= 10) {
    RimeCandidateListIterator iterator{};
    if (api->candidate_list_from_index(session_id, &iterator, page * size)) {
      while (api->candidate_list_next(&iterator)) {
        if (texts.size() == static_cast<size_t>(size)) { has_next = true; break; }
        texts.emplace_back(iterator.candidate.text ? iterator.candidate.text : "");
        comments.emplace_back(iterator.candidate.comment ? iterator.candidate.comment : "");
      }
      api->candidate_list_end(&iterator);
    }
  }
  jclass type = env->FindClass("dev/zeroinput/engine/rime/NativeCandidatePage");
  if (!type || env->ExceptionCheck()) return nullptr;
  jmethodID constructor = env->GetMethodID(type, "<init>", "([Ljava/lang/String;[Ljava/lang/String;Z)V");
  if (!constructor) return nullptr;
  jobjectArray values = ToStringArray(env, texts);
  jobjectArray hints = ToStringArray(env, comments);
  jobject result = env->NewObject(type, constructor, values, hints, static_cast<jboolean>(has_next));
  env->DeleteLocalRef(values);
  env->DeleteLocalRef(hints);
  env->DeleteLocalRef(type);
  return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_zeroinput_engine_rime_NativeRimeBridge_nativeSelectAbsoluteCandidate(
    JNIEnv*, jobject, jlong session_id, jint index) {
  std::lock_guard<std::mutex> lock(engine_mutex);
  return api && session_id && index >= 0 && api->select_candidate(
      static_cast<RimeSessionId>(session_id), static_cast<size_t>(index)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_zeroinput_engine_rime_NativeRimeBridge_nativeChangePage(
    JNIEnv*, jobject, jlong session_id, jboolean backwards) {
  std::lock_guard<std::mutex> lock(engine_mutex);
  return api && api->change_page(static_cast<RimeSessionId>(session_id), backwards)
             ? JNI_TRUE
             : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_zeroinput_engine_rime_NativeRimeBridge_nativeClearComposition(
    JNIEnv*, jobject, jlong session_id) {
  std::lock_guard<std::mutex> lock(engine_mutex);
  if (api) api->clear_composition(static_cast<RimeSessionId>(session_id));
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_zeroinput_engine_rime_NativeRimeBridge_nativeVersion(
    JNIEnv* env, jobject) {
  std::lock_guard<std::mutex> lock(engine_mutex);
  return ToJString(env, api ? api->get_version() : "");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_zeroinput_engine_rime_NativeRimeBridge_nativeSetInput(
    JNIEnv* env, jobject, jlong session_id, jstring input) {
  const auto value = ToUtf8(env, input);
  if (value.size() > 128 || !std::all_of(value.begin(), value.end(), [](char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= '2' && ch <= '9') || ch == '\'';
      })) return JNI_FALSE;
  std::lock_guard<std::mutex> lock(engine_mutex);
  return api && session_id && api->set_input(session_id, value.c_str()) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_zeroinput_engine_rime_NativeRimeBridge_nativeSetCaret(
    JNIEnv*, jobject, jlong session_id, jint position) {
  std::lock_guard<std::mutex> lock(engine_mutex);
  if (!api || !session_id || position < 0 || position > 128) return JNI_FALSE;
  const char* input = api->get_input(session_id);
  if (!input || static_cast<size_t>(position) > std::char_traits<char>::length(input)) return JNI_FALSE;
  api->set_caret_pos(session_id, static_cast<size_t>(position));
  return JNI_TRUE;
}
