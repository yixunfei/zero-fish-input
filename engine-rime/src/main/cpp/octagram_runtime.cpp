#include "octagram.h"
#include <cerrno>
#include <string>
#include <sys/stat.h>

void rime_require_module_octagram();

// Keep plugin details behind this engine-local boundary and force static registration.
bool ZeroInputLoadGrammar(const std::string& shared_directory) {
  rime_require_module_octagram();
  struct stat metadata {};
  const auto model = shared_directory + "/wanxiang-lts-zh-hans.gram";
  // Lite must initialize from a fresh directory without an optional grammar.
  // lstat distinguishes absence from a dangling managed link, which must fail.
  if (lstat(model.c_str(), &metadata) != 0) return errno == ENOENT;
  auto* component = dynamic_cast<rime::OctagramComponent*>(rime::Grammar::Require("grammar"));
  return component && component->GetDb("wanxiang-lts-zh-hans");
}
