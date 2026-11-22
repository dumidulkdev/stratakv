#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
mvn -q install -DskipTests
echo "=== JMH (subset) ==="
java -jar stratakv-benchmarks/target/benchmarks.jar StrataKvBenchmarks -wi 1 -i 2 -f 1 -t 1 2>/dev/null | tail -20
echo "=== Simple runner ==="
CP="stratakv-benchmarks/target/classes:stratakv-core/target/classes:$(mvn -q -pl stratakv-benchmarks dependency:build-classpath -Dmdep.outputFile=/dev/stdout)"
java -cp "$CP" io.stratakv.benchmark.SimpleBenchmarkRunner 3000