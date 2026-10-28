@echo off
setlocal
cd /d "%~dp0"

echo === TransactionGuard demo ===============================================
echo Resetting the local H2 database so the sample feed is replayed.
echo ===================================================================
if exist data rmdir /s /q data

call mvnw.cmd -q -DskipTests package
if errorlevel 1 (
    echo Build failed.
    exit /b 1
)

echo.
echo Starting TransactionGuard on http://localhost:8080 (Ctrl+C to stop) ...
echo The sample feed is ingested automatically; one transient failure is
echo injected for TXN-2005. Try:
echo.
echo   curl -s http://localhost:8080/api/v1/processing/summary
echo   curl -s http://localhost:8080/api/v1/transactions/TXN-2005
echo.

java -jar target\transaction-guard-1.0.0.jar --spring.profiles.active=demo
