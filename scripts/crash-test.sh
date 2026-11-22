#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
mvn -q -pl stratakv-core package -DskipTests
CP="stratakv-core/target/classes:$(mvn -q -pl stratakv-core dependency:build-classpath -Dmdep.outputFile=/dev/stdout)"
java -cp "$CP" io.stratakv.crash.CrashTestRunner "${1:-20}"