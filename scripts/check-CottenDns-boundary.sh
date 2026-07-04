#!/usr/bin/env bash
set -euo pipefail

base_ref="${1:-origin/main}"
head_ref="${2:-HEAD}"

if ! git rev-parse --verify "${base_ref}" >/dev/null 2>&1; then
  echo "Base ref '${base_ref}' is not available." >&2
  exit 2
fi

changed_files="$(
  git diff --name-only "${base_ref}...${head_ref}" -- 'third_party/CottenDns' || true
)"

if [[ -z "${changed_files}" ]]; then
  echo "No vendored CottenDns engine changes detected."
  exit 0
fi

echo "Vendored CottenDns engine changes detected:" >&2
echo "${changed_files}" >&2
echo >&2
echo "WhiteDNS builds third_party/CottenDns as its vendored CottenDns engine." >&2
echo "Add the 'allow-cottendns-engine' pull request label only when these changes are intentional engine maintenance." >&2
exit 1
