#pragma once

#include <jni.h>
#include <new>

namespace zero_jni {
// Never replace a pending Java exception or include native error/content text.
inline void Throw(JNIEnv* env, const char* type, const char* message) noexcept {
  if (env->ExceptionCheck()) return;
  jclass klass = env->FindClass(type);
  if (!klass || env->ExceptionCheck()) return;
  env->ThrowNew(klass, message);
  env->DeleteLocalRef(klass);
}

// Called only from an export's catch block, after resource leases unwind.
inline void TranslateException(JNIEnv* env) noexcept {
  try {
    throw;
  } catch (const std::bad_alloc&) {
    Throw(env, "java/lang/OutOfMemoryError", "Native engine allocation failed");
  } catch (...) {
    Throw(env, "java/lang/IllegalStateException", "Native engine operation failed");
  }
}

class StringChars {
 public:
  StringChars(JNIEnv* env, jstring value)
      : env_(env), value_(value), chars_(env->GetStringChars(value, nullptr)) {}
  ~StringChars() { if (chars_) env_->ReleaseStringChars(value_, chars_); }
  const jchar* get() const { return chars_; }
  StringChars(const StringChars&) = delete;
  StringChars& operator=(const StringChars&) = delete;

 private:
  JNIEnv* env_;
  jstring value_;
  const jchar* chars_;
};
}  // namespace zero_jni
