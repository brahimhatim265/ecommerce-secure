@echo off
rem ===========================================================================
rem verify-pki.bat : controle objectif des artefacts de pki\generated
rem Enveloppe mince autour de VerifyPki.java (JDK 17 ou plus, aucune dependance).
rem
rem Usage :   pki\verify-pki.bat
rem Variable : PKI_PASSWORD (mot de passe des keystores ; demonstration si absent)
rem Code de sortie : 0 = tous les controles PASS, 1 = au moins un FAIL,
rem                  2 = dossier pki\generated introuvable
rem ===========================================================================
setlocal

rem %~dp0 se termine par un "\" : on le retire, car "dossier\" entre guillemets
rem serait lu comme un guillemet echappe par Java.
set "SCRIPT_DIR=%~dp0"
if "%SCRIPT_DIR:~-1%"=="\" set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"

where java >nul 2>&1
if errorlevel 1 (
  echo ERREUR : java est introuvable. Installez un JDK 17 ou plus et 1>&2
  echo          verifiez que JAVA_HOME\bin est dans le PATH. 1>&2
  exit /b 1
)

java "%SCRIPT_DIR%\VerifyPki.java" "%SCRIPT_DIR%"
exit /b %ERRORLEVEL%