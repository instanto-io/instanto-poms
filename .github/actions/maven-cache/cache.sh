#!/usr/bin/env bash
# Keep public downloads outside the job repository wiped by the runner hook.
set -euo pipefail

CENTRAL="https://repo.maven.apache.org/maven2"
FALLBACK="https://maven-central.storage-download.googleapis.com/maven2"
PROBE="/org/apache/ant/ant/1.10.15/ant-1.10.15.pom"

MODE="${1:-}"
shift || true
MAVEN_ARGS=""
for arg in "$@"; do
  case "$arg" in
    --maven-args=*) MAVEN_ARGS="${arg#--maven-args=}" ;;
  esac
done

if [[ "$MODE" != "prepare" && "$MODE" != "save" ]]; then
  echo "Usage: cache.sh <prepare|save> [--maven-args=...]" >&2
  exit 1
fi

resolve_local_repo() {
  local repo="$HOME/.m2/repository"
  local token
  # Parse tokens splitting on whitespace (handles unquoted or simple quoted -Dmaven.repo.local=...)
  for token in $MAVEN_ARGS; do
    token="${token%\"}"
    token="${token#\"}"
    token="${token%\'}"
    token="${token#\'}"
    if [[ "$token" == -Dmaven.repo.local=* ]]; then
      repo="${token#-Dmaven.repo.local=}"
    fi
  done
  if [[ -n "${RUNNER_TEMP:-}" ]]; then
    repo="${repo//\$\{RUNNER_TEMP\}/$RUNNER_TEMP}"
    repo="${repo//\$RUNNER_TEMP/$RUNNER_TEMP}"
  fi
  if [[ "$repo" == "~/"* ]]; then
    repo="$HOME/${repo#\~/}"
  fi
  mkdir -p "$repo"
  (cd "$repo" && pwd -P)
}

copy_file_atomic() {
  local src="$1"
  local dst="$2"
  if [[ -L "$src" || ! -f "$src" ]]; then
    return 0
  fi
  mkdir -p "$(dirname "$dst")"
  local tmp="${dst}.cache-tmp"
  cp -f "$src" "$tmp"
  mv -f "$tmp" "$dst"
}

normalize_remote_marker() {
  local src="$1"
  local dst="$2"
  mkdir -p "$(dirname "$dst")"
  local tmp="${dst}.cache-tmp"
  awk '
    /^#/ || index($0, ">") == 0 || index($0, "=") == 0 { print; next }
    {
      eq = index($0, "=")
      left = substr($0, 1, eq - 1)
      right = substr($0, eq + 1)
      gt = 0
      for (i = length(left); i >= 1; i--) {
        if (substr(left, i, 1) == ">") { gt = i; break }
      }
      if (gt > 0) {
        name = substr(left, 1, gt - 1)
        origin = substr(left, gt + 1)
        if (origin == "central" || origin == "instanto-central") {
          print name ">central=" right
          next
        }
      }
      print
    }
  ' "$src" > "$tmp"
  mv -f "$tmp" "$dst"
}

restore_cache() {
  local cache="$1"
  local repo="$2"
  local count=0
  if [[ ! -d "$cache" ]]; then
    echo 0
    return 0
  fi
  while IFS= read -r -d '' src; do
    if [[ -L "$src" || ! -f "$src" ]]; then
      continue
    fi
    local rel="${src#"$cache"/}"
    local dst="$repo/$rel"
    if [[ "$(basename "$src")" == "_remote.repositories" ]]; then
      normalize_remote_marker "$src" "$dst"
    else
      copy_file_atomic "$src" "$dst"
    fi
    count=$((count + 1))
  done < <(find "$cache" -type f -print0)
  echo "$count"
}

save_cache() {
  local repo="$1"
  local cache="$2"
  local count=0
  if [[ ! -d "$repo" ]]; then
    echo 0
    return 0
  fi
  while IFS= read -r -d '' marker; do
    if [[ -L "$marker" || ! -f "$marker" ]]; then
      continue
    fi
    local dir
    dir="$(dirname "$marker")"
    local rel="${dir#"$repo"/}"
    if [[ "$rel" == "io/instanto"* || "$rel" == *"SNAPSHOT"* ]]; then
      continue
    fi
    local has_blank=0
    local names=()
    while IFS= read -r line || [[ -n "$line" ]]; do
      line="${line%$'\r'}"
      if [[ "$line" == "#"* || "$line" != *">"* || "$line" != *"="* ]]; then
        continue
      fi
      local left="${line%%=*}"
      local name="${left%>*}"
      local origin="${left##*>}"
      if [[ -z "$origin" ]]; then
        has_blank=1
        break
      fi
      if [[ ( "$origin" == "central" || "$origin" == "instanto-central" ) && "$(basename "$name")" == "$name" ]]; then
        names+=("$name")
      fi
    done < "$marker"
    if [[ "$has_blank" -eq 1 || "${#names[@]}" -eq 0 ]]; then
      continue
    fi
    local tracked=()
    local name
    for name in "${names[@]}"; do
      local src="$dir/$name"
      if [[ -L "$src" || ! -f "$src" ]]; then
        continue
      fi
      copy_file_atomic "$src" "$cache/$rel/$name"
      local suffix
      for suffix in .sha1 .sha256 .sha512 .md5 .asc; do
        if [[ -f "${src}${suffix}" && ! -L "${src}${suffix}" ]]; then
          copy_file_atomic "${src}${suffix}" "$cache/$rel/${name}${suffix}"
        fi
      done
      tracked+=("${name}>central=")
      count=$((count + 1))
    done
    if [[ "${#tracked[@]}" -gt 0 ]]; then
      mkdir -p "$cache/$rel"
      printf '%s\n' "${tracked[@]}" > "$cache/$rel/_remote.repositories"
    fi
  done < <(find "$repo" -name "_remote.repositories" -print0)
  echo "$count"
}

reachable() {
  local base="${1%/}"
  curl -fsSI --max-time 8 "${base}${PROBE}" >/dev/null 2>&1
}

resolve_central_mirror() {
  local configured="${1:-}"
  if [[ -n "$configured" ]]; then
    if [[ "$configured" != https://* || "$configured" == https://*@* ]]; then
      echo "MAVEN_CENTRAL_MIRROR_URL must be an HTTPS URL without credentials" >&2
      exit 1
    fi
    local host_part="${configured#https://}"
    host_part="${host_part%%/*}"
    if [[ -z "$host_part" ]]; then
      echo "MAVEN_CENTRAL_MIRROR_URL must be an HTTPS URL without credentials" >&2
      exit 1
    fi
    echo "${configured%/}"
    return 0
  fi
  if reachable "$CENTRAL"; then
    echo ""
    return 0
  fi
  if reachable "$FALLBACK"; then
    echo "Central unavailable or rate-limited; using Google's public Central mirror" >&2
    echo "$FALLBACK"
    return 0
  fi
  echo "Neither Maven Central nor its fallback mirror is reachable" >&2
  exit 1
}

ROOT="${MAVEN_DOWNLOAD_CACHE:-$HOME/.local/share/instanto-maven-cache/v1}"
CACHE="$ROOT/central"
REPOSITORY="$(resolve_local_repo)"
mkdir -p "$CACHE" "$REPOSITORY"

if [[ "$MODE" == "prepare" ]]; then
  RESTORED="$(restore_cache "$CACHE" "$REPOSITORY")"
  WRAPPER="$ROOT/wrapper-home"
  mkdir -p "$WRAPPER"
  MIRROR="$(resolve_central_mirror "${MAVEN_CENTRAL_MIRROR_URL:-}")"
  if [[ -n "${GITHUB_ENV:-}" ]]; then
    {
      echo "MAVEN_DOWNLOAD_CACHE=$ROOT"
      echo "MAVEN_USER_HOME=$WRAPPER"
      if [[ -n "$MIRROR" ]]; then
        echo "MAVEN_CENTRAL_MIRROR_URL=$MIRROR"
        echo "MVNW_REPOURL=$MIRROR"
      fi
    } >> "$GITHUB_ENV"
  fi
  echo "Runner download cache: restored $RESTORED files into $REPOSITORY"
  echo "Maven wrapper cache: $WRAPPER"
else
  SAVED="$(save_cache "$REPOSITORY" "$CACHE")"
  echo "Runner download cache: saved $SAVED Central release artifacts"
fi
