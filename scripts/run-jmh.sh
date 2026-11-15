#!/usr/bin/env bash
set -euo pipefail

# Reproducible raw-result capture. No benchmark scores are bundled or invented.
# Run from any directory: ./scripts/run-jmh.sh raw|sequencer|buffered|sync
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

PROFILE="${1:-raw}"
case "$PROFILE" in
  raw) FILTER='dev.matchline.benchmark.RawMatchingBenchmark' ; EXTRA='' ;;
  sequencer) FILTER='dev.matchline.benchmark.SequencerBenchmark' ; EXTRA='' ;;
  buffered) FILTER='dev.matchline.benchmark.JournalBenchmark' ; EXTRA='-p durability=OS_BUFFERED' ;;
  sync) FILTER='dev.matchline.benchmark.JournalBenchmark' ; EXTRA='-p durability=SYNC_EACH_RECORD' ;;
  *) echo "Usage: $0 raw|sequencer|buffered|sync" >&2; exit 2 ;;
esac

if [[ -n "${MATCHLINE_BENCH_TAG:-}" && ! "$MATCHLINE_BENCH_TAG" =~ ^[A-Za-z0-9_.-]{1,40}$ ]]; then
  echo "MATCHLINE_BENCH_TAG must be 1..40 safe filename characters" >&2
  exit 2
fi
TAG="${MATCHLINE_BENCH_TAG:-$(date -u +%Y%m%dT%H%M%SZ)}"
DEST="build/benchmarks/${PROFILE}-${TAG}"
if [[ -e "$DEST" ]]; then echo "Already exists: $DEST (choose a new MATCHLINE_BENCH_TAG)" >&2; exit 1; fi
mkdir -p "$DEST"
{
  echo "profile=$PROFILE"
  echo "timestamp_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "git_commit=$(git rev-parse HEAD 2>/dev/null || echo unknown)"
  echo "git_dirty=$(test -z "$(git status --porcelain 2>/dev/null)" && echo false || echo true)"
  echo "os=$(uname -a)"
  echo "cpu=$( (grep -m1 'model name' /proc/cpuinfo || true) | cut -d: -f2- | xargs)"
  echo "processors=$(getconf _NPROCESSORS_ONLN)"
  echo "JVM:"
  java -version 2>&1
  echo "Gradle wrapper distribution:"
  grep distributionUrl gradle/wrapper/gradle-wrapper.properties
  echo "JMH: org.openjdk.jmh 1.37, -f 1 -wi 2 -i 3 -w 2s -r 3s (journal overrides)"
  echo "Command: ./gradlew jmh --args='<benchmark> <options> -rf json -rff ...'"
} > "$DEST/environment.txt"

if [[ "$PROFILE" == "sync" || "$PROFILE" == "buffered" ]]; then
  # Journal benchmarking can be dominated by filesystem/fsync latency.
  TIME_OPTIONS='-f 1 -wi 1 -i 2 -w 1s -r 2s'
else
  TIME_OPTIONS='-f 1 -wi 2 -i 3 -w 2s -r 3s'
fi

# Deliberately no docker benchmark: filesystem and CPU limits vary greatly.
./gradlew jmh --no-daemon --max-workers=1 --console=plain \
  --args="$FILTER $EXTRA $TIME_OPTIONS -rf json -rff $DEST/results.json" \
  2>&1 | tee "$DEST/jmh-console.log"

sha256sum "$DEST/results.json" > "$DEST/results.sha256"
echo "Raw benchmark artifacts: $DEST/"
echo "Do not compare figures across different machines/JDKs/load/durability policies."
