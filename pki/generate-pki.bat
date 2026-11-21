@echo off
rem ===========================================================================
rem generate-pki.bat : PKI de demonstration du projet ecommerce-secure (etape 6)
rem
rem Equivalent Windows de generate-pki.sh : memes artefacts, memes noms.
rem
rem Usage :   pki\generate-pki.bat [/force]
rem   /force   supprime les artefacts existants et repart d'un etat propre
rem
rem Variables d'environnement (valeurs de demonstration si absentes) :
rem   PKI_PASSWORD      mot de passe des keystores et du truststore
rem   PKI_CA_PASSWORD   mot de passe de la cle privee de la CA
rem
rem Les mots de passe sont transmis a keytool par variable d'environnement
rem (-storepass:env / -keypass:env) : ils n'apparaissent jamais dans une commande.
rem
rem Conventions de ce fichier :
rem   - chaque argument contenant des virgules ou des "=" est entre guillemets
rem   - une commande longue est coupee avec ^ en fin de ligne (sans espace apres)
rem   - apres chaque "call", on teste "if errorlevel 1" pour s'arreter a la 1re erreur
rem   - messages en ASCII (sans accents) pour un affichage correct dans cmd
rem ===========================================================================
setlocal EnableExtensions

rem --- Chemins (tous relatifs au dossier du script, rien en dehors de pki\) ----
set "SCRIPT_DIR=%~dp0"
if "%SCRIPT_DIR:~-1%"=="\" set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"
set "GEN_DIR=%SCRIPT_DIR%\generated"
set "CA_DIR=%SCRIPT_DIR%\ca-private"
set "WORK_DIR=%SCRIPT_DIR%\.work"
set "NEG_DIR=%GEN_DIR%\negative-tests"
set "CA_STORE=%CA_DIR%\ca.p12"
set "OTHER_CA_STORE=%CA_DIR%\other-ca.p12"
set "CA_CERT=%GEN_DIR%\ca.crt"
set "TRUSTSTORE=%GEN_DIR%\truststore.p12"

rem --- Contrat d'identite ------------------------------------------------------
set "DN_SUFFIX=OU=ecommerce-secure, O=ENSIAS, C=MA"
set "CA_DN=CN=ecommerce-secure Internal CA, %DN_SUFFIX%"
set "OTHER_CA_DN=CN=Untrusted Other CA, OU=negative-tests, O=Unknown, C=MA"

rem --- Options -----------------------------------------------------------------
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

rem --- keytool disponible ? ----------------------------------------------------
where keytool >nul 2>&1
if errorlevel 1 (
  echo ERREUR : keytool est introuvable. Installez un JDK 17 ou plus et 1>&2
  echo          verifiez que JAVA_HOME\bin est dans le PATH. 1>&2
  exit /b 1
)
set "KT_VER="
for /f "delims=" %%V in ('keytool -version 2^>^&1 ^| findstr /v /b /c:"Picked up"') do set "KT_VER=%%V"
echo keytool : %KT_VER%

rem --- Mots de passe (jamais affiches) -----------------------------------------
if not defined PKI_PASSWORD (
  set "PKI_PASSWORD=ecommerce-demo-pki"
  echo AVERTISSEMENT : PKI_PASSWORD absent, mot de passe de DEMONSTRATION utilise.
)
if not defined PKI_CA_PASSWORD (
  set "PKI_CA_PASSWORD=ecommerce-demo-pki"
  echo AVERTISSEMENT : PKI_CA_PASSWORD absent, mot de passe de DEMONSTRATION utilise.
)

rem --- Protection contre l'ecrasement ------------------------------------------
set "HAS_ARTIFACTS=0"
if exist "%CA_STORE%" set "HAS_ARTIFACTS=1"
for /f "delims=" %%A in ('dir /b "%GEN_DIR%" 2^>nul') do set "HAS_ARTIFACTS=1"
for /f "delims=" %%A in ('dir /b "%CA_DIR%" 2^>nul') do set "HAS_ARTIFACTS=1"
if "%HAS_ARTIFACTS%"=="0" goto prepare_dirs
if "%FORCE%"=="1" goto do_force
echo ERREUR : des artefacts PKI existent deja (pki/generated ou pki/ca-private). 1>&2
echo          Relancez avec /force pour les supprimer et tout regenerer. 1>&2
exit /b 1

:do_force
echo Option /force : suppression des artefacts existants.
if exist "%GEN_DIR%" rd /s /q "%GEN_DIR%"
if exist "%CA_DIR%" rd /s /q "%CA_DIR%"
if exist "%GEN_DIR%" (
  echo ERREUR : impossible de supprimer pki\generated, un fichier est peut-etre utilise. 1>&2
  exit /b 1
)
if exist "%CA_DIR%" (
  echo ERREUR : impossible de supprimer pki\ca-private, un fichier est peut-etre utilise. 1>&2
  exit /b 1
)

:prepare_dirs
if not exist "%GEN_DIR%" mkdir "%GEN_DIR%"
if not exist "%CA_DIR%" mkdir "%CA_DIR%"
if not exist "%WORK_DIR%" mkdir "%WORK_DIR%"
if not exist "%NEG_DIR%" mkdir "%NEG_DIR%"

rem --- Deroulement ; le dossier temporaire est supprime a la fin (succes ou erreur)
call :run
set "RC=%ERRORLEVEL%"
if exist "%WORK_DIR%" rd /s /q "%WORK_DIR%"
exit /b %RC%


rem ===========================================================================
rem Deroulement principal
rem ===========================================================================
:run
echo [1] Creation de la CA interne (RSA 4096, 3650 jours)
call :create_ca "%CA_STORE%" "%CA_DN%" 4096 3650 "%CA_CERT%"
if errorlevel 1 exit /b 1

for %%S in (user-service order-service payment-service authorization-server) do (
  call :do_service %%S
  if errorlevel 1 exit /b 1
)

echo [3] Truststore commun (la CA seule)
call :kt -importcert -alias ca -file "%CA_CERT%" -noprompt ^
  -keystore "%TRUSTSTORE%" -storetype PKCS12 -storepass:env PKI_PASSWORD
if errorlevel 1 exit /b 1

echo [4] Certificats negatifs (tests d'echec des etapes 7 et 8)
rem rogue-service : signe par la CA du projet et valide, mais CN hors de la liste
call :issue_cert "rogue-service" "%NEG_DIR%\rogue-service\rogue-service.p12" ^
  "%WORK_DIR%\rogue-service.crt" "%CA_STORE%" "%CA_CERT%" 365 "-1d"
if errorlevel 1 exit /b 1

rem expired-order-service : CN order-service, signe par la CA du projet, mais
rem deja expire (debut il y a 400 jours, validite de 30 jours)
call :issue_cert "order-service" "%NEG_DIR%\expired-order-service\expired-order-service.p12" ^
  "%WORK_DIR%\expired-order-service.crt" "%CA_STORE%" "%CA_CERT%" 30 "-400d"
if errorlevel 1 exit /b 1

rem untrusted-order-service : CN order-service, signe par une AUTRE CA creee
rem uniquement pour ce cas (hors depot, jamais ajoutee au truststore)
call :create_ca "%OTHER_CA_STORE%" "%OTHER_CA_DN%" 2048 365 "%WORK_DIR%\other-ca.crt"
if errorlevel 1 exit /b 1
call :issue_cert "order-service" "%NEG_DIR%\untrusted-order-service\untrusted-order-service.p12" ^
  "%WORK_DIR%\untrusted-order-service.crt" "%OTHER_CA_STORE%" "%WORK_DIR%\other-ca.crt" 365 "-1d"
if errorlevel 1 exit /b 1

call :print_summary
exit /b 0


rem ===========================================================================
rem Sous-programmes
rem ===========================================================================

:usage
echo Usage : generate-pki.bat [/force]
echo   /force   supprime les artefacts existants et regenere tout
exit /b 0

rem --- Execution de keytool : sortie masquee, affichee seulement en cas d'echec
:kt
keytool %* >"%WORK_DIR%\kt.log" 2>&1
if errorlevel 1 (
  echo ERREUR : keytool a echoue : 1>&2
  findstr /v /b /c:"Picked up" "%WORK_DIR%\kt.log" 1>&2
  exit /b 1
)
exit /b 0

rem --- Creation d'une CA
rem Arguments : keystore de la CA, sujet, taille RSA, validite en jours, fichier .crt
:create_ca
rem Debut un jour avant : le certificat de la CA n'est jamais plus recent
rem que les certificats qu'elle signe.
call :kt -genkeypair -alias ca -keyalg RSA -keysize %~3 -sigalg SHA256withRSA ^
  -dname "%~2" -validity %~4 -startdate "-1d" ^
  -ext "bc:critical=ca:true,pathlen:0" ^
  -ext "ku:critical=keyCertSign,cRLSign" ^
  -keystore "%~1" -storetype PKCS12 ^
  -storepass:env PKI_CA_PASSWORD -keypass:env PKI_CA_PASSWORD
if errorlevel 1 exit /b 1
rem Export du certificat PUBLIC de la CA (jamais la cle privee)
call :kt -exportcert -alias ca -rfc -file "%~5" ^
  -keystore "%~1" -storetype PKCS12 -storepass:env PKI_CA_PASSWORD
exit /b %ERRORLEVEL%

rem --- Certificat d'un des 4 services, signe par la CA du projet
rem Argument : nom du service
:do_service
echo [2] Certificat de %~1 (RSA 2048, 365 jours)
call :issue_cert "%~1" "%GEN_DIR%\%~1\%~1.p12" "%GEN_DIR%\%~1\%~1.crt" ^
  "%CA_STORE%" "%CA_CERT%" 365 "-1d"
exit /b %ERRORLEVEL%

rem --- Emission d'un certificat signe par une CA
rem Arguments : 1=CN (= alias)  2=keystore a creer  3=fichier .crt signe
rem             4=keystore de la CA signataire  5=certificat public de cette CA
rem             6=validite en jours  7=date de debut (ex : "-1d")
:issue_cert
if not exist "%~dp2" mkdir "%~dp2"
set "CSR=%WORK_DIR%\%~n2.csr"

rem a) paire de cles dans le keystore du service
call :kt -genkeypair -alias "%~1" -keyalg RSA -keysize 2048 -sigalg SHA256withRSA ^
  -dname "CN=%~1, %DN_SUFFIX%" -validity 365 ^
  -keystore "%~2" -storetype PKCS12 ^
  -storepass:env PKI_PASSWORD -keypass:env PKI_PASSWORD
if errorlevel 1 exit /b 1

rem b) demande de signature (CSR)
call :kt -certreq -alias "%~1" -file "%CSR%" ^
  -keystore "%~2" -storetype PKCS12 -storepass:env PKI_PASSWORD
if errorlevel 1 exit /b 1

rem c) signature par la CA. Piege : -gencert ne recopie PAS les extensions de
rem la CSR, il faut donc les passer explicitement avec -ext.
call :kt -gencert -alias ca -infile "%CSR%" -outfile "%~3" -rfc ^
  -validity %~6 -startdate "%~7" -sigalg SHA256withRSA ^
  -ext "bc=ca:false" ^
  -ext "ku:critical=digitalSignature,keyEncipherment" ^
  -ext "eku=serverAuth,clientAuth" ^
  -ext "san=dns:localhost,dns:%~1,ip:127.0.0.1" ^
  -keystore "%~4" -storetype PKCS12 ^
  -storepass:env PKI_CA_PASSWORD -keypass:env PKI_CA_PASSWORD
if errorlevel 1 exit /b 1

rem d) import de la CA (necessaire pour construire la chaine), puis du
rem certificat signe : le keystore contient alors la chaine [service, CA]
call :kt -importcert -alias ca -file "%~5" -noprompt ^
  -keystore "%~2" -storetype PKCS12 -storepass:env PKI_PASSWORD
if errorlevel 1 exit /b 1
call :kt -importcert -alias "%~1" -file "%~3" -noprompt ^
  -keystore "%~2" -storetype PKCS12 -storepass:env PKI_PASSWORD
exit /b %ERRORLEVEL%

rem --- Liste des fichiers de pki\generated, avec des "/" comme dans le .sh
:print_files
setlocal EnableDelayedExpansion
for /f "delims=" %%F in ('dir /s /b /a-d "%GEN_DIR%" ^| sort') do (
  set "REL=%%F"
  set "REL=!REL:%GEN_DIR%\=!"
  set "REL=!REL:\=/!"
  echo   !REL!
)
endlocal
exit /b 0

rem --- Dates de validite d'un certificat. Arguments : libelle, fichier .crt
rem Le format de sortie de keytool -printcert est lu en anglais (JDK standard).
:print_dates
set "DATES_FOUND="
for /f "tokens=3*" %%A in ('keytool -printcert -file "%~2" 2^>^&1 ^| findstr /b /c:"Valid from:"') do (
  set "DATES_FOUND=1"
  echo   %~1 : %%A %%B
)
if not defined DATES_FOUND echo   %~1 : ^(dates non disponibles, voir keytool -printcert -file "%~2"^)
exit /b 0

rem --- Recapitulatif (aucun mot de passe)
:print_summary
echo.
echo ================ RECAPITULATIF ================
echo Artefacts generes dans pki/generated :
call :print_files
echo Cle privee de la CA (hors depot) : pki/ca-private/ca.p12
echo.
set "FP="
for /f "tokens=2" %%H in ('keytool -printcert -file "%CA_CERT%" 2^>^&1 ^| findstr /c:"SHA256:"') do set "FP=%%H"
if not defined FP set "FP=(non disponible, voir keytool -printcert -file pki/generated/ca.crt)"
echo Empreinte SHA-256 de la CA :
echo   %FP%
echo.
echo Dates de validite (debut jusqu'a expiration) :
call :print_dates ca "%CA_CERT%"
for %%S in (user-service order-service payment-service authorization-server) do call :print_dates %%S "%GEN_DIR%\%%S\%%S.crt"
echo ===============================================
echo Verification : java pki/VerifyPki.java   (ou pki\verify-pki.bat)
exit /b 0