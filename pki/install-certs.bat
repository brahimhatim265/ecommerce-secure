@echo off
rem ===========================================================================
rem install-certs.bat : installe les certificats de la PKI dans les 4 modules
rem                     (etape 7 : HTTPS et mTLS)
rem
rem Equivalent Windows de install-certs.sh : memes fichiers, memes noms.
rem
rem Copie, depuis pki\generated\ :
rem   MODULE\src\main\resources\certs\keystore.p12     keystore du service
rem   MODULE\src\main\resources\certs\truststore.p12   truststore commun
rem   MODULE\src\test\resources\certs\NOM.p12        fixtures de test
rem
rem Usage :   pki\install-certs.bat [/force]
rem   /force   ecrase les fichiers existants dont le contenu est different
rem            (sans cette option, un fichier different n'est JAMAIS ecrase)
rem
rem Pour chaque fichier : copie / identique / DIFFERENT (non ecrase) / ecrase.
rem Si au moins un fichier est DIFFERENT, le script se termine avec le code 1.
rem
rem Securite : seuls les fichiers de pki\generated\ sont lus. La cle privee de la
rem CA (pki\ca-private\) n'est jamais lue ni copiee.
rem
rem Apres une regeneration de la PKI (pki\generate-pki.bat /force), relancez ce
rem script avec /force. Les certificats de demonstration sont valables 365 jours.
rem
rem Conventions de ce fichier (comme generate-pki.bat) :
rem   - messages en ASCII (sans accents) pour un affichage correct dans cmd
rem   - pas de bloc entre parentheses autour des chemins : on utilise des goto
rem ===========================================================================
setlocal EnableExtensions

rem --- Chemins (tous deduits de l'emplacement du script) ----------------------
set "SCRIPT_DIR=%~dp0"
if "%SCRIPT_DIR:~-1%"=="\" set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"
for %%I in ("%SCRIPT_DIR%\..") do set "ROOT_DIR=%%~fI"
set "GEN_DIR=%SCRIPT_DIR%\generated"
set "NEG_DIR=%GEN_DIR%\negative-tests"
set "TRUSTSTORE=%GEN_DIR%\truststore.p12"

rem --- Options ---------------------------------------------------------------
set "FORCE=0"
:parse_args
if "%~1"=="" goto args_done
if /i "%~1"=="/force" goto arg_force
if "%~1"=="/?" goto arg_help
echo Option inconnue : %~1 1>&2
call :usage 1>&2
exit /b 2
:arg_force
set "FORCE=1"
shift
goto parse_args
:arg_help
call :usage
exit /b 0
:args_done

set "CONFLICTS=0"
set "ERRORS=0"

rem --- Execution -------------------------------------------------------------
echo Installation des certificats [force=%FORCE%]

for %%M in (user-service order-service payment-service authorization-server) do call :install_main %%M

rem Fixtures de test : ce dont chaque module a besoin pour ses tests TLS.
rem user-service et payment-service jouent le serveur : le client de test presente
rem le certificat d'order-service, d'authorization-server ou un certificat negatif.
echo [user-service] test
call :install_test user-service "%GEN_DIR%\order-service\order-service.p12"
call :install_test user-service "%GEN_DIR%\authorization-server\authorization-server.p12"
call :install_test user-service "%NEG_DIR%\rogue-service\rogue-service.p12"
call :install_test user-service "%NEG_DIR%\expired-order-service\expired-order-service.p12"
call :install_test user-service "%NEG_DIR%\untrusted-order-service\untrusted-order-service.p12"

echo [payment-service] test
call :install_test payment-service "%GEN_DIR%\order-service\order-service.p12"
call :install_test payment-service "%GEN_DIR%\authorization-server\authorization-server.p12"
call :install_test payment-service "%NEG_DIR%\rogue-service\rogue-service.p12"
call :install_test payment-service "%NEG_DIR%\expired-order-service\expired-order-service.p12"
call :install_test payment-service "%NEG_DIR%\untrusted-order-service\untrusted-order-service.p12"

rem order-service et authorization-server jouent le client : un petit serveur HTTPS
rem de test presente le certificat du service vise (ou un certificat non fiable).
echo [order-service] test
call :install_test order-service "%GEN_DIR%\user-service\user-service.p12"
call :install_test order-service "%GEN_DIR%\payment-service\payment-service.p12"
call :install_test order-service "%GEN_DIR%\authorization-server\authorization-server.p12"
call :install_test order-service "%NEG_DIR%\untrusted-order-service\untrusted-order-service.p12"

echo [authorization-server] test
call :install_test authorization-server "%GEN_DIR%\user-service\user-service.p12"
call :install_test authorization-server "%NEG_DIR%\untrusted-order-service\untrusted-order-service.p12"

rem --- Bilan -----------------------------------------------------------------
if %ERRORS% GTR 0 goto summary_errors
if %CONFLICTS% GTR 0 goto summary_conflicts
echo Termine : les certificats sont installes.
exit /b 0

:summary_errors
echo ECHEC : %ERRORS% source[s] introuvable[s]. 1>&2
exit /b 1

:summary_conflicts
echo ATTENTION : %CONFLICTS% fichier[s] different[s] non ecrase[s]. 1>&2
echo             Relancez avec /force pour les remplacer. 1>&2
exit /b 1

rem ===========================================================================
rem Sous-routines
rem ===========================================================================

:usage
echo Usage : pki\install-certs.bat [/force]
echo   /force   ecrase les fichiers existants dont le contenu est different
exit /b 0

rem --- install_main MODULE : keystore du service + truststore commun ----------
:install_main
echo [%~1] main
call :install_file "%GEN_DIR%\%~1\%~1.p12" "%~1" main keystore.p12
call :install_file "%TRUSTSTORE%" "%~1" main truststore.p12
exit /b 0

rem --- install_test MODULE SOURCE : fixture de test, nom d'origine conserve ---
:install_test
call :install_file "%~2" "%~1" test "%~nx2"
exit /b 0

rem --- install_file SOURCE MODULE main ou test NOM_DESTINATION -------------------
:install_file
set "SRC=%~1"
set "REL=%~2\src\%~3\resources\certs"
set "DST_DIR=%ROOT_DIR%\%REL%"
set "DST=%DST_DIR%\%~4"
if not exist "%SRC%" goto install_missing_source
if not exist "%DST_DIR%\" mkdir "%DST_DIR%"
if not exist "%DST%" goto install_copy
fc /b "%SRC%" "%DST%" >nul 2>&1
if not errorlevel 1 goto install_same
if "%FORCE%"=="1" goto install_overwrite
echo   DIFFERENT   : %REL%\%~4 [non ecrase, utilisez /force] 1>&2
set /a CONFLICTS+=1
exit /b 0

:install_missing_source
echo   ERREUR      : source introuvable : %SRC% 1>&2
echo                 [lancez d'abord pki\generate-pki.bat] 1>&2
set /a ERRORS+=1
exit /b 0

:install_same
echo   identique   : %REL%\%~4
exit /b 0

:install_overwrite
copy /y "%SRC%" "%DST%" >nul
if errorlevel 1 goto install_copy_failed
echo   ecrase      : %REL%\%~4
exit /b 0

:install_copy
copy /y "%SRC%" "%DST%" >nul
if errorlevel 1 goto install_copy_failed
echo   copie       : %REL%\%~4
exit /b 0

:install_copy_failed
echo   ERREUR      : copie impossible vers %DST% 1>&2
set /a ERRORS+=1
exit /b 0