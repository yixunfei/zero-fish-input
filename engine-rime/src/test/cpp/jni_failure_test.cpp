#include "jni_strings.h"
#include <cassert>
#include <cstdlib>
#include <cstdio>
#include <cstring>
#include <stdexcept>

namespace {
bool fail_native_allocation = false;
bool pending = false;
int fail_at = 0;
int calls = 0;
int releases = 0;
const char* thrown_type = nullptr;

bool Fail() {
  assert(!pending);  // Any allocation/lookup after a Java failure is a defect.
  if (++calls == fail_at) { pending = true; return true; }
  return false;
}
jboolean JNICALL ExceptionCheck(JNIEnv*) { return pending; }
jclass JNICALL FindClass(JNIEnv*, const char*) {
  return Fail() ? nullptr : reinterpret_cast<jclass>(1);
}
jint JNICALL ThrowNew(JNIEnv*, jclass, const char* message) {
  assert(!pending);
  assert(std::strstr(message, "private") == nullptr);
  pending = true;
  return 0;
}
void JNICALL DeleteLocalRef(JNIEnv*, jobject) {}
jobjectArray JNICALL NewObjectArray(JNIEnv*, jsize, jclass klass, jobject) {
  assert(klass);
  return Fail() ? nullptr : reinterpret_cast<jobjectArray>(2);
}
jstring JNICALL NewString(JNIEnv*, const jchar*, jsize) {
  return Fail() ? nullptr : reinterpret_cast<jstring>(3);
}
void JNICALL SetObjectArrayElement(JNIEnv*, jobjectArray array, jsize, jobject value) {
  assert(array && value);
  Fail();
}
jsize JNICALL GetStringLength(JNIEnv*, jstring) { assert(!pending); return 1024; }
const jchar* JNICALL GetStringChars(JNIEnv*, jstring, jboolean*) {
  static jchar text[1024] = {};
  return Fail() ? nullptr : text;
}
void JNICALL ReleaseStringChars(JNIEnv*, jstring, const jchar*) { ++releases; }
void Reset(int failure = 0) { pending = false; calls = 0; fail_at = failure; thrown_type = nullptr; }
jclass JNICALL RecordingFindClass(JNIEnv* env, const char* type) {
  thrown_type = type;
  return FindClass(env, type);
}
}  // namespace

void* operator new(std::size_t size) {
  if (fail_native_allocation) { fail_native_allocation = false; throw std::bad_alloc(); }
  if (void* value = std::malloc(size)) return value;
  throw std::bad_alloc();
}
void operator delete(void* value) noexcept { std::free(value); }
void operator delete(void* value, std::size_t) noexcept { std::free(value); }

int main() {
  JNINativeInterface table{};
  table.ExceptionCheck = ExceptionCheck;
  table.FindClass = RecordingFindClass;
  table.ThrowNew = ThrowNew;
  table.DeleteLocalRef = DeleteLocalRef;
  table.NewObjectArray = NewObjectArray;
  table.NewString = NewString;
  table.SetObjectArrayElement = SetObjectArrayElement;
  table.GetStringLength = GetStringLength;
  table.GetStringChars = GetStringChars;
  table.ReleaseStringChars = ReleaseStringChars;
  JNIEnv env{&table};
  const std::vector<std::string> values{"public", "fixture"};
  Reset();
  assert(zero_jni::ToStringArray(&env, values));
  const int allocation_steps = calls;
  for (int step = 1; step <= allocation_steps; ++step) {
    Reset(step);
    assert(!zero_jni::ToStringArray(&env, values));
    assert(pending && calls == step);
  }
  Reset();
  pending = true;
  try { throw std::runtime_error("private fixture"); }
  catch (...) { zero_jni::TranslateException(&env); }
  assert(calls == 0);  // Keep the original Java exception, even under OOM.
  Reset();
  fail_native_allocation = true;
  try { zero_jni::ToUtf8(&env, reinterpret_cast<jstring>(3)); assert(false); }
  catch (...) { zero_jni::TranslateException(&env); }
  assert(releases == 1 && pending);
  assert(std::strcmp(thrown_type, "java/lang/OutOfMemoryError") == 0);
  Reset();
  fail_native_allocation = true;
  try { zero_jni::ToJString(&env, "public fixture"); assert(false); }
  catch (...) { zero_jni::TranslateException(&env); }
  assert(pending && std::strcmp(thrown_type, "java/lang/OutOfMemoryError") == 0);
  Reset(1);
  try { throw 1; } catch (...) { zero_jni::TranslateException(&env); }
  assert(pending && calls == 1);  // Failed exception-class lookup must stop too.
  Reset();
  try { throw 1; } catch (...) { zero_jni::TranslateException(&env); }
  assert(pending && std::strcmp(thrown_type, "java/lang/IllegalStateException") == 0);
  Reset(1);
  assert(zero_jni::ToUtf8(&env, reinterpret_cast<jstring>(3)).empty());
  assert(pending && releases == 1);
  std::puts("JNI failure injection passed");
}
