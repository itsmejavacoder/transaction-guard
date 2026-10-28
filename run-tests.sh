#!/usr/bin/env bash
# Cross-platform equivalent of run-tests.cmd.
set -euo pipefail
cd "$(dirname "$0")"

echo "=== TransactionGuard test suite =========================================="
if ! ./mvnw test; then
    echo
    echo "TESTS FAILED - see target/surefire-reports"
    exit 1
fi

echo
echo "All tests passed. Reports: target/surefire-reports"
