#include "octagram.h"

void rime_require_module_octagram();

// Keep plugin details behind this engine-local boundary and force static registration.
bool ZeroInputLoadGrammar() {
  rime_require_module_octagram();
  auto* component = dynamic_cast<rime::OctagramComponent*>(rime::Grammar::Require("grammar"));
  return component && component->GetDb("wanxiang-lts-zh-hans");
}
