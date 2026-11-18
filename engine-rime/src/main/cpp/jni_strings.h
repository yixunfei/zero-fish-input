#pragma once

#include "jni_boundary.h"
#include <cstdint>
#include <string>
#include <vector>

namespace zero_jni {
inline void AppendUtf8(std::string& output, std::uint32_t code_point) {
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

inline std::string ToUtf8(JNIEnv* env, jstring value) {
  if (!value) return {};
  const jsize length = env->GetStringLength(value);
  StringChars lease(env, value);
  const jchar* chars = lease.get();
  if (!chars || env->ExceptionCheck()) return {};
  std::string output;
  output.reserve(static_cast<std::size_t>(length) * 3);
  for (jsize index = 0; index < length; ++index) {
    std::uint32_t code_point = chars[index];
    if (code_point >= 0xd800 && code_point <= 0xdbff && index + 1 < length) {
      const std::uint32_t low = chars[index + 1];
      if (low >= 0xdc00 && low <= 0xdfff) {
        code_point = 0x10000 + ((code_point - 0xd800) << 10) + (low - 0xdc00);
        ++index;
      } else {
        code_point = 0xfffd;
      }
    } else if (code_point >= 0xd800 && code_point <= 0xdfff) {
      code_point = 0xfffd;
    }
    AppendUtf8(output, code_point);
  }
  return output;
}

inline std::uint32_t DecodeUtf8CodePoint(const unsigned char*& cursor,
                                  const unsigned char* end) {
  const unsigned char first = *cursor++;
  if (first < 0x80) return first;
  if (first < 0xc2 || first > 0xf4) return 0xfffd;
  const int continuation_count = first < 0xe0 ? 1 : (first < 0xf0 ? 2 : 3);
  std::uint32_t value = first & (0x3f >> continuation_count);
  if (end - cursor < continuation_count) return 0xfffd;
  for (int index = 0; index < continuation_count; ++index) {
    const unsigned char next = *cursor;
    if ((next & 0xc0) != 0x80) return 0xfffd;
    ++cursor;
    value = (value << 6) | (next & 0x3f);
  }
  const std::uint32_t minimum = continuation_count == 1 ? 0x80 :
      (continuation_count == 2 ? 0x800 : 0x10000);
  return value >= minimum && value <= 0x10ffff &&
      !(value >= 0xd800 && value <= 0xdfff) ? value : 0xfffd;
}

inline jstring ToJString(JNIEnv* env, const char* value) {
  static const jchar empty = 0;
  if (!value || !*value) return env->NewString(&empty, 0);
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

inline jobjectArray ToStringArray(JNIEnv* env,
                           const std::vector<std::string>& values) {
  if (env->ExceptionCheck()) return nullptr;
  jclass string_class = env->FindClass("java/lang/String");
  if (!string_class || env->ExceptionCheck()) return nullptr;
  jobjectArray result =
      env->NewObjectArray(static_cast<jsize>(values.size()), string_class, nullptr);
  env->DeleteLocalRef(string_class);
  if (!result || env->ExceptionCheck()) return nullptr;
  for (std::size_t index = 0; index < values.size(); ++index) {
    jstring value = ToJString(env, values[index].c_str());
    if (!value || env->ExceptionCheck()) return nullptr;
    env->SetObjectArrayElement(result, static_cast<jsize>(index), value);
    env->DeleteLocalRef(value);
    if (env->ExceptionCheck()) return nullptr;
  }
  return result;
}

}  // namespace zero_jni
