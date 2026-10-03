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
# Gradle needs JDK 25+ (see README.md); bytecode still targets 17.
JAVA_HOME="$HOME/.jdks/corretto-26.0.2.1" ./gradlew signPlugin
ls -la build/distributions/*-signed.zip
