@echo off
setlocal
cd /d "%~dp0"

echo === TransactionGuard test suite ==========================================
call mvnw.cmd test
if errorlevel 1 (
    echo.
    echo TESTS FAILED - see target\surefire-reports
    exit /b 1
)

echo.
echo All tests passed. Reports: target\surefire-reports
