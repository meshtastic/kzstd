#!/usr/bin/env bash
# Builds the kzstd API docs (HTML) with the Dokka 2.3.0 CLI.
#
#   scripts/dokka.sh <output-dir>
#
# Every jar is fetched from Maven Central and checked against a pinned SHA-256. The plugin set is
# Dokka's own base plugins plus kotlinx-html 0.8.1: 0.9.1 and later fail in HtmlRenderer with a
# NoSuchMethodError on Dokka 2.3.0. Do not add dokka-core to the plugin set; the CLI already has it.
set -euo pipefail

out="${1:?usage: scripts/dokka.sh <output-dir>}"
root="$(cd "$(dirname "$0")/.." && pwd)"
cache="${DOKKA_CACHE_DIR:-${RUNNER_TEMP:-${TMPDIR:-/tmp}}/dokka-2.3.0}"
mkdir -p "$cache" "$out"
out="$(cd "$out" && pwd)"

fetch() { # <sha256> <maven path>
  local sum="$1" path="$2" file="$cache/${2##*/}"
  [ -f "$file" ] || curl -fsSL -o "$file" "https://repo1.maven.org/maven2/$path"
  echo "$sum  $file" | shasum -a 256 -c - >/dev/null || {
    echo "::error::checksum mismatch for $path" >&2
    exit 1
  }
  printf '%s' "$file"
}

cli=$(fetch 9c9e05b3cf19e8f794064e8290180f709721189317821ec0a7968598b8f3b422 org/jetbrains/dokka/dokka-cli/2.3.0/dokka-cli-2.3.0.jar)
plugins=(
  "$(fetch 25f9cce85729aa04d5f906cd67cbac3cdf43e144899cd5344b31355e4700c397 org/jetbrains/dokka/dokka-base/2.3.0/dokka-base-2.3.0.jar)"
  "$(fetch 8989b3b175eb06d5107273b5848b4d9f0c02fe2f4f905df22f4eb2979898a3d6 org/jetbrains/dokka/analysis-kotlin-symbols/2.3.0/analysis-kotlin-symbols-2.3.0.jar)"
  "$(fetch 5397fa0b8d0bcb88780fd2f1c23c73c0313cc7d12ec4f6e24a14f9ff6c1e1d56 org/jetbrains/dokka/analysis-markdown/2.3.0/analysis-markdown-2.3.0.jar)"
  "$(fetch a081779cde78ac0770a55dc868ff03e683dabbc58c4f21603dbb52f2093ef375 org/jetbrains/dokka/templating-plugin/2.3.0/templating-plugin-2.3.0.jar)"
  "$(fetch 98bda1c78a5028a134ceb25b63f5c130c89349730d35fd47ef7490b6bf0b63b3 org/jetbrains/kotlinx/kotlinx-html-jvm/0.8.1/kotlinx-html-jvm-0.8.1.jar)"
  "$(fetch 04d65ec1bde6cea20e3495d5e78ef96ab774d9936434861d3254bd88e7e94f92 org/freemarker/freemarker/2.3.32/freemarker-2.3.32.jar)"
  "$(fetch 28df038cf2d4fdc337cbf44ff053041270ee5812b5605d9d1dfdb33ff7b8b664 com/github/curious-odd-man/rgxgen/2.0/rgxgen-2.0.jar)"
)

config="$cache/dokka.json"
{
  printf '{\n  "moduleName": "kzstd",\n  "outputDir": "%s",\n' "$out"
  printf '  "pluginsClasspath": ['
  for i in "${!plugins[@]}"; do
    [ "$i" -eq 0 ] || printf ', '
    printf '"%s"' "${plugins[$i]}"
  done
  printf '],\n'
  printf '  "sourceSets": [\n    { "sourceSetID": {"scopeId": "kzstd", "sourceSetName": "common"},'
  printf ' "displayName": "common", "analysisPlatform": "common", "sourceRoots": ["%s/kzstd/src"] }\n  ]\n}\n' "$root"
} > "$config"

java -jar "$cli" "$config"
