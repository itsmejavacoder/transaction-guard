#!/usr/bin/env bash
# Cross-platform equivalent of run-demo.cmd.
set -euo pipefail
cd "$(dirname "$0")"

echo "=== TransactionGuard demo ==============================================="
echo "Resetting the local H2 database so the sample feed is replayed."
echo "======================================================================="
rm -rf data

./mvnw -q -DskipTests package

echo
echo "Starting TransactionGuard on http://localhost:8080 (Ctrl+C to stop) ..."
echo "The sample feed is ingested automatically; one transient failure is"
echo "injected for TXN-2005. Try:"
echo
echo "  curl -s http://localhost:8080/api/v1/processing/summary"
echo "  curl -s http://localhost:8080/api/v1/transactions/TXN-2005"
echo

exec java -jar target/transaction-guard-1.0.0.jar --spring.profiles.active=demo
