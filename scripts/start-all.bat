@echo off
REM Starts the 4 services in the right order: user-service, authorization-server, payment-service, then order-service.
REM One console window per service. Close the windows to stop the services.
setlocal
set "ROOT=%~dp0.."
cd /d "%ROOT%"

REM The services listen in HTTPS only: their certificates must have been installed (pki\install-certs.bat).
for %%S in (user-service authorization-server payment-service order-service) do if not exist "%%S\src\main\resources\certs\keystore.p12" goto certs_missing
goto certs_ok
:certs_missing
echo ERROR: the certificates are not installed in the modules. Run pki\install-certs.bat first.
exit /b 1
:certs_ok

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
echo   user-service    : https://localhost:8081/swagger-ui.html
echo   order-service   : https://localhost:8082/swagger-ui.html
echo   payment-service : https://localhost:8083/swagger-ui.html
echo   authorization-server : https://localhost:9000/.well-known/openid-configuration
echo   HTTPS with the project CA: browsers and Postman must trust pki\generated\ca.crt (see docs\mtls-services.md).
endlocal
