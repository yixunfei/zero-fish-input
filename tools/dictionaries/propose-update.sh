#!/usr/bin/env bash
# Update only this workflow's bot branch; never approve, merge or publish data.
set -euo pipefail
paths=(tools/dictionaries/sources.lock.json tools/resources/catalog.json
       LICENSES/wanxiang-CC-BY-4.0.txt engine-rime/prebuilt/rime/prebuilt)
if git diff --quiet -- "${paths[@]}"; then exit 0; fi
branch=zero/weekly-public-dictionaries
commit_subject='Update pinned Wanxiang dictionaries and grammar model'
git config user.name 'github-actions[bot]'
git config user.email '41898282+github-actions[bot]@users.noreply.github.com'
expected_tip=''
if git ls-remote --exit-code --heads origin "$branch" >/dev/null; then
  git fetch origin "$branch:refs/remotes/origin/$branch"
  expected_tip=$(git rev-parse "refs/remotes/origin/$branch")
  author=$(git show -s --format=%an "$expected_tip")
  subject=$(git show -s --format=%s "$expected_tip")
  if [[ "$author" != 'github-actions[bot]' || "$subject" != "$commit_subject" ]]; then
    echo "Refusing to overwrite a branch not created by this workflow"
    exit 1
  fi
fi
git switch -c "$branch"
git add -- "${paths[@]}"
git commit -m "$commit_subject"
if [[ -n "$expected_tip" ]]; then
  git push --force-with-lease="refs/heads/$branch:$expected_tip" origin "$branch"
else
  git push origin "$branch"
fi
body=$(mktemp)
trap 'rm -f "$body"' EXIT
cat > "$body" <<'EOF'
Updates all sixteen Wanxiang dictionary outputs, the simplified Chinese grammar model and matching default Rime prebuilt artifacts. Fourteen tables track the pinned data repository; lianxiang and renming retain their explicitly recorded reviewed source. Downloads and normalized outputs are hash-verified.

Review source provenance, licensing, the pinned model digest and default artifacts. Run Android Rime contract/device checks and measure package size and cold deployment before merging. Host generation verification is not Android/device acceptance. This workflow never approves or merges a PR and never publishes resources or an APK.

After reviewed merge, dispatch the manual resource publication workflow with the full reviewed commit SHA reachable from main. Verify public assets and companion sources before distributing the updated catalog/application.
EOF
if gh pr view "$branch" >/dev/null 2>&1; then
  gh pr edit "$branch" --title 'Update bundled Wanxiang dictionaries and model' --body-file "$body"
else
  gh pr create --head "$branch" --title 'Update bundled Wanxiang dictionaries and model' --body-file "$body"
fi
