#!/usr/bin/env bash
#
# Copyright (c) 2026, cwi-swat
# All rights reserved. This file is licensed under the BSD 2-Clause
# License -- see the LICENSE file in this directory.
#
# Signs the plugin zip (build/distributions/rascal-intellij-<version>-signed.zip)
# with the cwi-swat key, for uploading to the JetBrains Marketplace. See
# README.md ("Publishing a release") for the one-time key setup.
#
#   ./sign.sh                    sign with the existing certificate (normal release)
#   ./sign.sh --new-certificate  first (re)create chain.crt from SUBJECT below,
#                                e.g. to change the name in it, or once it expires
#
# Asks for the key's PEM pass phrase once; nothing secret is stored.
set -euo pipefail

SIGNING_DIR=~/.rascal-intellij-signing
# The (public) name in the certificate. Change it here, then run with --new-certificate.
SUBJECT="/C=NL/ST=Noord-Holland/L=Amsterdam/O=cwi-swat/OU=Software Analysis and Transformation/CN=cwi-swat"
CERT_DAYS=3650

new_certificate=false
case "${1:-}" in
  "") ;;
  --new-certificate) new_certificate=true ;;
  *) echo "usage: $0 [--new-certificate]" >&2; exit 2 ;;
esac

# Gradle needs a JDK 25+ (see README.md; the plugin's bytecode still targets 17).
# Works on Linux and macOS: GRADLE_JAVA_HOME if set, else JDKs under ~/.jdks
# (newest Corretto 26 first; macOS bundles have their home in Contents/Home),
# else macOS's /usr/libexec/java_home, else JAVA_HOME.
is_jdk25plus() {
  [ -x "$1/bin/java" ] && "$1/bin/java" -version 2>&1 | grep -Eq 'version "(2[5-9]|[3-9][0-9])'
}
find_gradle_jdk() {
  local candidate
  for candidate in "${GRADLE_JAVA_HOME:-}" "$HOME"/.jdks/corretto-26* "$HOME"/.jdks/*; do
    [ -n "$candidate" ] && [ -d "$candidate" ] || continue
    [ -d "$candidate/Contents/Home" ] && candidate="$candidate/Contents/Home"
    if is_jdk25plus "$candidate"; then echo "$candidate"; return 0; fi
  done
  if [ -x /usr/libexec/java_home ] && candidate=$(/usr/libexec/java_home -v 25+ 2>/dev/null); then
    echo "$candidate"; return 0
  fi
  if [ -n "${JAVA_HOME:-}" ] && is_jdk25plus "$JAVA_HOME"; then echo "$JAVA_HOME"; return 0; fi
  return 1
}
GRADLE_JDK=$(find_gradle_jdk) || {
  echo "No JDK 25+ found (looked in GRADLE_JAVA_HOME, ~/.jdks, /usr/libexec/java_home, JAVA_HOME)." >&2
  echo "Install one (e.g. via IntelliJ: Project Structure > SDKs > Download JDK), or set GRADLE_JAVA_HOME." >&2
  exit 1
}

export CERTIFICATE_CHAIN_FILE="$SIGNING_DIR/chain.crt"
export PRIVATE_KEY_FILE="$SIGNING_DIR/private_encrypted.pem"

read -rs -p "PEM pass phrase: " PRIVATE_KEY_PASSWORD; echo
export PRIVATE_KEY_PASSWORD

# Fail early, with a clear message, if the pass phrase is wrong.
if ! openssl pkey -in "$PRIVATE_KEY_FILE" -noout -passin env:PRIVATE_KEY_PASSWORD 2>/dev/null; then
  echo "Wrong pass phrase for $PRIVATE_KEY_FILE." >&2
  exit 1
fi

if $new_certificate; then
  openssl req -key "$PRIVATE_KEY_FILE" -passin env:PRIVATE_KEY_PASSWORD \
    -new -x509 -days "$CERT_DAYS" -out "$CERTIFICATE_CHAIN_FILE" -subj "$SUBJECT"
  echo "New certificate:"
  openssl x509 -in "$CERTIFICATE_CHAIN_FILE" -noout -subject -enddate
fi

cd "$(dirname "$0")"
# --rerun: always sign afresh. Otherwise Gradle skips signPlugin as "up-to-date"
# when the unsigned zip and key are unchanged, silently keeping the old file.
JAVA_HOME="$GRADLE_JDK" ./gradlew signPlugin --rerun
ls -la build/distributions/*-signed.zip
