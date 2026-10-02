#!/usr/bin/env bash
# Compiles this project's Rascal sources (src/main/rascal/) and resolves
# its Maven dependencies into ~/.m2 -- run this once before opening the
# project in IntelliJ (see README.md). Without it,
# target/classes stays empty and the Rascal IntelliJ plugin's classpath
# computation (RascalTerminalSupport.computeDependencyClasspath) has
# nothing to find.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
mvn -f "$SCRIPT_DIR/pom.xml" clean compile dependency:resolve
