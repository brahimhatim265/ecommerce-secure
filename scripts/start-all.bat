@echo off
REM Starts the 4 services in the right order: user-service, authorization-server, payment-service, then order-service.
REM One console window per service. Close the windows to stop the services.
setlocal
set "ROOT=%~dp0.."
cd /d "%ROOT%"

echo Starting user-service...
start "user-service" cmd /k "mvnw.cmd -q -pl user-service spring-boot:run"
timeout /t 15 /nobreak >nul

echo Starting authorization-server...
start "authorization-server" cmd /k "mvnw.cmd -q -pl authorization-server spring-boot:run"
timeout /t 15 /nobreak >nul

echo Starting payment-service...
start "payment-service" cmd /k "mvnw.cmd -q -pl payment-service spring-boot:run"
timeout /t 15 /nobreak >nul

echo Starting order-service...
start "order-service" cmd /k "mvnw.cmd -q -pl order-service spring-boot:run"

echo.
echo   user-service    : http://localhost:8081/swagger-ui.html
echo   order-service   : http://localhost:8082/swagger-ui.html
echo   payment-service : http://localhost:8083/swagger-ui.html
echo   authorization-server : http://localhost:9000/.well-known/openid-configuration
endlocal
