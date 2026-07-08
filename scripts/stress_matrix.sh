#!/bin/bash
# Run a simple concurrency matrix against an already-running Mini-SSP app.
#
# Examples:
#   ./scripts/stress_matrix.sh
#   ./scripts/stress_matrix.sh --scenario latency100 --duration 30 --concurrency "50 100 200 400"

set -e

URL="http://localhost:8080/api/v1/bid"
SLOT="slot-test-001"
DURATION=30
SCENARIO="perf"
CONCURRENCY_LIST="50 100 200 400"
OUT_DIR="test-results/stress-$(date +%Y%m%d-%H%M%S)"

while [ $# -gt 0 ]; do
  case "$1" in
    --url) URL="$2"; shift 2 ;;
    --slot) SLOT="$2"; shift 2 ;;
    --duration) DURATION="$2"; shift 2 ;;
    --scenario) SCENARIO="$2"; shift 2 ;;
    --concurrency) CONCURRENCY_LIST="$2"; shift 2 ;;
    --out-dir) OUT_DIR="$2"; shift 2 ;;
    --help|-h)
      sed -n '2,9p' "$0" | sed 's/^# \?//'
      exit 0
      ;;
    *)
      echo "Unknown arg: $1"
      exit 1
      ;;
  esac
done

mkdir -p "$OUT_DIR"
CSV="$OUT_DIR/results.csv"

echo "Mini-SSP stress matrix"
echo "URL: $URL"
echo "slot: $SLOT"
echo "duration: ${DURATION}s"
echo "concurrency: $CONCURRENCY_LIST"
echo "output: $CSV"
echo ""

for C in $CONCURRENCY_LIST; do
  python3 scripts/stress_test.py \
    --url "$URL" \
    --slot "$SLOT" \
    --concurrency "$C" \
    --duration "$DURATION" \
    --scenario "${SCENARIO}-c${C}" \
    --csv-output "$CSV"
done

echo "Done. Results: $CSV"
