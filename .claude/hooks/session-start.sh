#!/usr/bin/env bash
#
# SessionStart hook – připraví toolchain, který BotAlive vyžaduje, do webových
# session Claude Code (claude.ai/code).
#
# Proč vůbec existuje:
#   Projekt cílí na JDK 25 (Paper API 26.1 – viz gradle/libs.versions.toml) a
#   Gradle 9.3.0 (verze zabudovaná v gradle/wrapper). Base image webových session
#   má ale JDK 21 a Gradle 8.14.3. Navíc oficiální distribuce se dnes stahují
#   z GitHub Releases (Temurin/Adoptium i gradle/gradle-distributions), a egress
#   policy session přístup k cizím GitHub repozitářům blokuje (HTTP 403). Proto:
#     - JDK 25 tahá z Amazon Corretto CDN (corretto.aws, neredirectuje na GitHub),
#     - Gradle 9.3.0 tahá z veřejného zrcadla, ale integritu ověřuje proti
#       oficiálnímu .sha256 (ten je dostupný přímo, redirectuje jen samotný .zip).
#
# Vlastnosti: idempotentní (bezpečné spustit vícekrát), neinteraktivní, běží
# JEN v remote (web) prostředí – lokální vývoj se nedotýká.
#
set -euo pipefail

# Logy jdou na stderr, ať stdout hooku zůstává čistý (obsah stdout se jinak
# přilepuje do kontextu agenta).
log() { echo "[session-start] $*" >&2; }

# --- Hook má smysl jen na webu; lokálně (kde má vývojář vlastní JDK) se vypne. ---
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"

# ---------------------------------------------------------------------------
# 1) JDK 25 (Amazon Corretto) – pinnutá verze + ověření SHA-256.
# ---------------------------------------------------------------------------
JDK_DIR="/opt/jdk-25"
JDK_VERSION="25.0.3.9.1"
JDK_URL="https://corretto.aws/downloads/resources/${JDK_VERSION}/amazon-corretto-${JDK_VERSION}-linux-x64.tar.gz"
JDK_SHA256="00486fa402136f8d40512b101c645dd4db9be2b5535171530ad241cd96c1223d"

install_jdk() {
  if [ -x "${JDK_DIR}/bin/java" ] && "${JDK_DIR}/bin/java" -version 2>&1 | grep -q 'version "25'; then
    log "JDK 25 už je nainstalované (${JDK_DIR})."
    return 0
  fi
  log "Stahuji Amazon Corretto JDK ${JDK_VERSION}..."
  local tmp; tmp="$(mktemp -d)"
  trap 'rm -rf "${tmp}"' RETURN
  curl -fsSL -o "${tmp}/jdk.tar.gz" "${JDK_URL}"
  echo "${JDK_SHA256}  ${tmp}/jdk.tar.gz" | sha256sum -c - >/dev/null
  log "Checksum JDK OK, rozbaluji do ${JDK_DIR}..."
  rm -rf "${JDK_DIR}"; mkdir -p "${JDK_DIR}"
  tar -xzf "${tmp}/jdk.tar.gz" -C "${JDK_DIR}" --strip-components=1
  log "JDK 25: $("${JDK_DIR}/bin/java" -version 2>&1 | head -1)"
}

# /usr/bin/java atd. přepneme na 25 (best-effort), ať i nástroje sahající přímo
# na systémové cesty dostanou 25. Selhání není fatální – hlavní cestu tvoří
# JAVA_HOME/PATH níže.
set_default_java() {
  command -v update-alternatives >/dev/null 2>&1 || return 0
  local t
  for t in java javac jar javadoc jshell keytool jarsigner; do
    [ -x "${JDK_DIR}/bin/${t}" ] || continue
    update-alternatives --install "/usr/bin/${t}" "${t}" "${JDK_DIR}/bin/${t}" 2500 >/dev/null 2>&1 || true
    update-alternatives --set "${t}" "${JDK_DIR}/bin/${t}" >/dev/null 2>&1 || true
  done
}

# ---------------------------------------------------------------------------
# 2) Gradle wrapper cache – naplníme distribucí ze zrcadla, aby ./gradlew
#    nemusel (marně) stahovat z GitHubu. Verzi bereme z wrapper properties,
#    ať hook přežije upgrade Gradle bez editace.
# ---------------------------------------------------------------------------
# Fallback checksum pro gradle-9.3.0-bin.zip (pro případ, že by oficiální
# .sha256 nešel stáhnout). Použije se jen když název zipu přesně sedí.
GRADLE_930_SHA256="0d585f69da091fc5b2beced877feab55a3064d43b8a1d46aeb07996b0915e0e0"

# Zrcadla, která hostují Gradle distribuce přímo (bez redirectu na GitHub).
GRADLE_MIRRORS=(
  "https://mirrors.cloud.tencent.com/gradle"
  "https://mirrors.aliyun.com/macports/distfiles/gradle"
)

GRADLE_HOME_RESOLVED=""

# base36(md5(url)) – přesně to, jak Gradle wrapper počítá jméno cache adresáře
# (PathAssembler: new BigInteger(1, md5).toString(36)).
wrapper_hash() {
  printf '%s' "$1" | python3 -c '
import sys, hashlib
d = hashlib.md5(sys.stdin.buffer.read()).digest()
n = int.from_bytes(d, "big")
a = "0123456789abcdefghijklmnopqrstuvwxyz"
s = ""
while n:
    s = a[n % 36] + s
    n //= 36
print(s or "0")'
}

seed_gradle_wrapper() {
  local props="${PROJECT_DIR}/gradle/wrapper/gradle-wrapper.properties"
  [ -f "${props}" ] || { log "Nenašel jsem ${props}, seed Gradle přeskočen."; return 0; }
  command -v python3 >/dev/null 2>&1 || { log "python3 chybí – nelze spočítat cache hash, seed přeskočen."; return 0; }

  # distributionUrl v .properties escapuje ':' jako '\:' – odescapovat.
  local dist_url
  dist_url="$(grep -E '^distributionUrl=' "${props}" | head -1 | cut -d= -f2- | sed 's/\\:/:/g' | tr -d '\r')"
  [ -n "${dist_url}" ] || { log "distributionUrl v properties chybí, seed přeskočen."; return 0; }

  local zip_name dist_name gradle_dirname
  zip_name="$(basename "${dist_url}")"                 # gradle-9.3.0-bin.zip
  dist_name="${zip_name%.zip}"                         # gradle-9.3.0-bin
  gradle_dirname="$(echo "${dist_name}" | sed -E 's/-(bin|all)$//')"  # gradle-9.3.0

  local ghome hash dist_dir gradle_home
  ghome="${GRADLE_USER_HOME:-${HOME}/.gradle}"
  hash="$(wrapper_hash "${dist_url}")"
  dist_dir="${ghome}/wrapper/dists/${dist_name}/${hash}"
  gradle_home="${dist_dir}/${gradle_dirname}"

  if [ -f "${dist_dir}/${zip_name}.ok" ] && [ -x "${gradle_home}/bin/gradle" ]; then
    log "Gradle ${dist_name} už je v cache."
    GRADLE_HOME_RESOLVED="${gradle_home}"
    return 0
  fi

  log "Naplňuji Gradle wrapper cache: ${dist_name} (hash ${hash})"
  rm -rf "${dist_dir}"; mkdir -p "${dist_dir}"
  local zip_path="${dist_dir}/${zip_name}" got_zip=0 base
  for base in "${GRADLE_MIRRORS[@]}"; do
    if curl -fsSL -o "${zip_path}" "${base}/${zip_name}"; then got_zip=1; break; fi
    log "Zrcadlo ${base} selhalo, zkouším další..."
  done
  [ "${got_zip}" = 1 ] || { log "‼️  Stažení Gradle ze zrcadel selhalo."; return 1; }

  # Integritu ověříme proti OFICIÁLNÍMU .sha256 (ten redirect na GitHub nemá).
  local want got
  want="$(curl -fsSL "${dist_url}.sha256" 2>/dev/null | tr -d '[:space:]' || true)"
  if ! echo "${want}" | grep -qE '^[0-9a-f]{64}$'; then
    if [ "${zip_name}" = "gradle-9.3.0-bin.zip" ]; then
      want="${GRADLE_930_SHA256}"
      log "Oficiální .sha256 nedostupný, používám pinnutý checksum pro 9.3.0."
    else
      log "‼️  Nemám čím ověřit checksum Gradle (${zip_name}); seed zrušen."; rm -rf "${dist_dir}"; return 1
    fi
  fi
  got="$(sha256sum "${zip_path}" | cut -d' ' -f1)"
  if [ "${want}" != "${got}" ]; then
    log "‼️  Checksum Gradle nesouhlasí (chtěl ${want}, dostal ${got}); seed zrušen."; rm -rf "${dist_dir}"; return 1
  fi
  log "Checksum Gradle OK, rozbaluji."
  ( cd "${dist_dir}" && unzip -q -o "${zip_name}" )
  # Marker, podle kterého wrapper pozná hotovou instalaci a přeskočí stahování.
  touch "${dist_dir}/${zip_name}.ok"
  GRADLE_HOME_RESOLVED="${gradle_home}"
  log "Gradle ${gradle_dirname} připraven v cache."
}

# ---------------------------------------------------------------------------
# 3) Perzistence do session prostředí (JAVA_HOME, PATH, GRADLE_HOME).
# ---------------------------------------------------------------------------
persist_env() {
  local f="${CLAUDE_ENV_FILE:-}"
  [ -n "${f}" ] || return 0
  add_env_line "export JAVA_HOME=${JDK_DIR}"
  add_env_line "export PATH=${JDK_DIR}/bin:\$PATH"
  if [ -n "${GRADLE_HOME_RESOLVED}" ]; then
    add_env_line "export GRADLE_HOME=${GRADLE_HOME_RESOLVED}"
    add_env_line "export PATH=${GRADLE_HOME_RESOLVED}/bin:\$PATH"
  fi
}
add_env_line() { grep -qxF "$1" "${CLAUDE_ENV_FILE}" 2>/dev/null || echo "$1" >> "${CLAUDE_ENV_FILE}"; }

# ---------------------------------------------------------------------------
main() {
  install_jdk
  export JAVA_HOME="${JDK_DIR}"
  export PATH="${JDK_DIR}/bin:${PATH}"
  set_default_java
  # Seed Gradle je best-effort: když selže, session i tak nastartuje a problém
  # je vidět v logu (builds by pak spadly na stahování – ale to je lepší než
  # blokovat start session).
  seed_gradle_wrapper || log "‼️  Seed Gradle wrapperu se nepovedl – ./gradlew se pokusí stáhnout sám."
  persist_env
  log "Hotovo: JDK 25 + Gradle wrapper připraveny pro tuto session."
}
main
