// Public-fixture oracle. Run with cwd pointing to an isolated test deployment.
#include <rime_api.h>
#include <cstring>
#include <iostream>
#include <string>

int main() {
  RIME_STRUCT(RimeTraits, traits);
  traits.app_name = "rime.verse_probe";
  traits.shared_data_dir = ".";
  traits.user_data_dir = "user";
  traits.prebuilt_data_dir = "build";
  traits.staging_dir = "build";
  traits.log_dir = "";
  traits.min_log_level = 3;
  auto api = rime_get_api();
  api->setup(&traits);
  api->initialize(&traits);
  auto session = api->create_session();
  if (!session || !api->select_schema(session, "zeroinput_pinyin")) return 1;
  std::string keys;
  while (std::getline(std::cin, keys)) {
    if (keys.empty() || keys.size() > 64 ||
        keys.find_first_not_of("abcdefghijklmnopqrstuvwxyz") != std::string::npos) return 2;
    api->clear_composition(session);
    for (char key : keys) api->process_key(session, key, 0);
    std::cout << keys;
    RimeCandidateListIterator it{};
    if (api->candidate_list_begin(session, &it)) {
      for (int i = 0; i < 32 && api->candidate_list_next(&it); ++i) {
        std::cout << '\t';
        // Empty column preserves rank for a nonrepresentable candidate.
        const char* word = it.candidate.text;
        if (word && std::strlen(word) <= 192 && !std::strpbrk(word, "\t\r\n")) std::cout << word;
      }
      api->candidate_list_end(&it);
    }
    std::cout << '\n';
  }
  api->destroy_session(session);
  api->finalize();
  return 0;
}
