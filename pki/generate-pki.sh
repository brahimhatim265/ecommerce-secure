#!/usr/bin/env bash
# =============================================================================
# generate-pki.sh : PKI de demonstration du projet ecommerce-secure (etape 6)
#
# Genere avec keytool (JDK) :
#   - une autorite de certification (CA) interne
#   - un keystore PKCS12 par service (cle privee + chaine de 2 certificats)
#   - un truststore commun (la CA seule) et les exports PEM publics
#   - 3 certificats "negatifs" pour les futurs tests d'echec du mTLS
#
# Usage :   ./pki/generate-pki.sh [--force]
#   --force   supprime les artefacts existants et repart d'un etat propre
#
# Variables d'environnement (valeurs de demonstration si absentes) :
#   PKI_PASSWORD      mot de passe des keystores et du truststore
#   PKI_CA_PASSWORD   mot de passe de la cle privee de la CA
#
# Les mots de passe sont transmis a keytool par variable d'environnement
# (-storepass:env / -keypass:env) : ils n'apparaissent jamais dans une commande.
#
# Les messages sont volontairement en ASCII (sans accents) : le script .bat
# equivalent doit s'afficher correctement dans la console Windows.
# =============================================================================
set -euo pipefail

# --- Chemins (tous relatifs au dossier du script, rien en dehors de pki/) -----
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GEN_DIR="$SCRIPT_DIR/generated"
CA_DIR="$SCRIPT_DIR/ca-private"
WORK_DIR="$SCRIPT_DIR/.work"
NEG_DIR="$GEN_DIR/negative-tests"
CA_STORE="$CA_DIR/ca.p12"
OTHER_CA_STORE="$CA_DIR/other-ca.p12"
CA_CERT="$GEN_DIR/ca.crt"
TRUSTSTORE="$GEN_DIR/truststore.p12"

# --- Contrat d'identite -------------------------------------------------------
SERVICES=(user-service order-service payment-service authorization-server)
DN_SUFFIX="OU=ecommerce-secure, O=ENSIAS, C=MA"
CA_DN="CN=ecommerce-secure Internal CA, $DN_SUFFIX"
OTHER_CA_DN="CN=Untrusted Other CA, OU=negative-tests, O=Unknown, C=MA"

usage() {
  echo "Usage : $0 [--force]"
  echo "  --force   supprime les artefacts existants et regenere tout"
}

# --- Options ------------------------------------------------------------------
FORCE=0
for arg in "$@"; do
  case "$arg" in
    --force)   FORCE=1 ;;
    -h|--help) usage; exit 0 ;;
    *)         echo "Option inconnue : $arg" >&2; usage >&2; exit 2 ;;
  esac
done

# --- keytool disponible ? -----------------------------------------------------
if ! command -v keytool >/dev/null 2>&1; then
  echo "ERREUR : keytool est introuvable. Installez un JDK 17 ou plus et" >&2
  echo "         verifiez que JAVA_HOME/bin est dans le PATH." >&2
  exit 1
fi
echo "keytool : $(keytool -version 2>&1 | grep -v '^Picked up' | head -n 1)"

# --- Mots de passe (jamais affiches) ------------------------------------------
if [ -z "${PKI_PASSWORD:-}" ]; then
  PKI_PASSWORD="ecommerce-demo-pki"
  echo "AVERTISSEMENT : PKI_PASSWORD absent, mot de passe de DEMONSTRATION utilise."
fi
if [ -z "${PKI_CA_PASSWORD:-}" ]; then
  PKI_CA_PASSWORD="ecommerce-demo-pki"
  echo "AVERTISSEMENT : PKI_CA_PASSWORD absent, mot de passe de DEMONSTRATION utilise."
fi
export PKI_PASSWORD PKI_CA_PASSWORD

# --- Execution de keytool : sortie masquee, affichee seulement en cas d'echec --
kt() {
  local out
  if ! out="$(keytool "$@" 2>&1)"; then
    echo "ERREUR : keytool $1 a echoue :" >&2
    printf '%s\n' "$out" | grep -v '^Picked up' >&2 || true
    exit 1
  fi
}

# --- Nettoyage du dossier temporaire a la sortie (succes ou erreur) -----------
trap 'rm -rf "$WORK_DIR"' EXIT

# --- Protection contre l'ecrasement -------------------------------------------
if [ -e "$CA_STORE" ] || [ -n "$(ls -A "$GEN_DIR" 2>/dev/null)" ] \
                      || [ -n "$(ls -A "$CA_DIR" 2>/dev/null)" ]; then
  if [ "$FORCE" -eq 0 ]; then
    echo "ERREUR : des artefacts PKI existent deja (pki/generated ou pki/ca-private)." >&2
    echo "         Relancez avec --force pour les supprimer et tout regenerer." >&2
    exit 1
  fi
  echo "--force : suppression des artefacts existants."
  rm -rf "$GEN_DIR" "$CA_DIR"
fi
mkdir -p "$GEN_DIR" "$CA_DIR" "$WORK_DIR" "$NEG_DIR"

# --- Creation d'une CA ----------------------------------------------------------
# Arguments : keystore de la CA, sujet, taille RSA, validite (jours), fichier .crt
create_ca() {
  local store="$1" dn="$2" bits="$3" days="$4" crt="$5"
  # Debut un jour avant : le certificat de la CA n'est jamais plus recent
  # que les certificats qu'elle signe.
  kt -genkeypair -alias ca -keyalg RSA -keysize "$bits" -sigalg SHA256withRSA \
     -dname "$dn" -validity "$days" -startdate "-1d" \
     -ext "bc:critical=ca:true,pathlen:0" \
     -ext "ku:critical=keyCertSign,cRLSign" \
     -keystore "$store" -storetype PKCS12 \
     -storepass:env PKI_CA_PASSWORD -keypass:env PKI_CA_PASSWORD
  # Export du certificat PUBLIC de la CA (jamais la cle privee)
  kt -exportcert -alias ca -rfc -file "$crt" \
     -keystore "$store" -storetype PKCS12 -storepass:env PKI_CA_PASSWORD
}

# --- Emission d'un certificat de service signe par une CA -----------------------
# Arguments : CN (= alias), keystore a creer, fichier .crt signe, keystore de la
#             CA signataire, certificat public de cette CA, validite, date de debut
issue_cert() {
  local cn="$1" ks="$2" crt="$3" signer="$4" signer_crt="$5" days="$6" start="$7"
  local csr
  csr="$WORK_DIR/$(basename "$ks" .p12).csr"
  mkdir -p "$(dirname "$ks")"

  # a) paire de cles dans le keystore du service
  kt -genkeypair -alias "$cn" -keyalg RSA -keysize 2048 -sigalg SHA256withRSA \
     -dname "CN=$cn, $DN_SUFFIX" -validity 365 \
     -keystore "$ks" -storetype PKCS12 \
     -storepass:env PKI_PASSWORD -keypass:env PKI_PASSWORD

  # b) demande de signature (CSR)
  kt -certreq -alias "$cn" -file "$csr" \
     -keystore "$ks" -storetype PKCS12 -storepass:env PKI_PASSWORD

  # c) signature par la CA. Piege : -gencert ne recopie PAS les extensions de
  #    la CSR, il faut donc les passer explicitement avec -ext.
  kt -gencert -alias ca -infile "$csr" -outfile "$crt" -rfc \
     -validity "$days" -startdate "$start" -sigalg SHA256withRSA \
     -ext "bc=ca:false" \
     -ext "ku:critical=digitalSignature,keyEncipherment" \
     -ext "eku=serverAuth,clientAuth" \
     -ext "san=dns:localhost,dns:$cn,ip:127.0.0.1" \
     -keystore "$signer" -storetype PKCS12 \
     -storepass:env PKI_CA_PASSWORD -keypass:env PKI_CA_PASSWORD

  # d) import de la CA (necessaire pour construire la chaine), puis du
  #    certificat signe : le keystore contient alors la chaine [service, CA]
  kt -importcert -alias ca -file "$signer_crt" -noprompt \
     -keystore "$ks" -storetype PKCS12 -storepass:env PKI_PASSWORD
  kt -importcert -alias "$cn" -file "$crt" -noprompt \
     -keystore "$ks" -storetype PKCS12 -storepass:env PKI_PASSWORD
}

# --- Affichage du recapitulatif (aucun mot de passe) ---------------------------
# keytool est force en anglais (-J-Duser.language=en) pour un format stable.
print_dates() {
  keytool -J-Duser.language=en -printcert -file "$1" 2>/dev/null | sed -n 's/^Valid from: *//p'
}

print_summary() {
  local fingerprint svc
  echo
  echo "================ RECAPITULATIF ================"
  echo "Artefacts generes dans pki/generated :"
  (cd "$GEN_DIR" && find . -type f | sed 's|^\./|  |' | sort)
  echo "Cle privee de la CA (hors depot) : pki/ca-private/ca.p12"
  echo
  fingerprint="$(keytool -J-Duser.language=en -printcert -file "$CA_CERT" 2>/dev/null \
                 | sed -n 's/^[[:space:]]*SHA256:[[:space:]]*//p')"
  echo "Empreinte SHA-256 de la CA :"
  echo "  $fingerprint"
  echo
  echo "Dates de validite (debut jusqu'a expiration) :"
  echo "  ca : $(print_dates "$CA_CERT")"
  for svc in "${SERVICES[@]}"; do
    echo "  $svc : $(print_dates "$GEN_DIR/$svc/$svc.crt")"
  done
  echo "==============================================="
  echo "Verification : java pki/VerifyPki.java   (ou pki/verify-pki.sh)"
}

# --- Deroulement --------------------------------------------------------------
echo "[1] Creation de la CA interne (RSA 4096, 3650 jours)"
create_ca "$CA_STORE" "$CA_DN" 4096 3650 "$CA_CERT"

for svc in "${SERVICES[@]}"; do
  echo "[2] Certificat de $svc (RSA 2048, 365 jours)"
  issue_cert "$svc" "$GEN_DIR/$svc/$svc.p12" "$GEN_DIR/$svc/$svc.crt" \
             "$CA_STORE" "$CA_CERT" 365 "-1d"
done

echo "[3] Truststore commun (la CA seule)"
kt -importcert -alias ca -file "$CA_CERT" -noprompt \
   -keystore "$TRUSTSTORE" -storetype PKCS12 -storepass:env PKI_PASSWORD

echo "[4] Certificats negatifs (tests d'echec des etapes 7 et 8)"
# rogue-service : signe par la CA du projet et valide, mais CN hors de la liste
issue_cert "rogue-service" "$NEG_DIR/rogue-service/rogue-service.p12" \
           "$WORK_DIR/rogue-service.crt" "$CA_STORE" "$CA_CERT" 365 "-1d"

# expired-order-service : CN order-service, signe par la CA du projet, mais
# deja expire (debut il y a 400 jours, validite de 30 jours)
issue_cert "order-service" "$NEG_DIR/expired-order-service/expired-order-service.p12" \
           "$WORK_DIR/expired-order-service.crt" "$CA_STORE" "$CA_CERT" 30 "-400d"

# untrusted-order-service : CN order-service, signe par une AUTRE CA creee
# uniquement pour ce cas (hors depot, jamais ajoutee au truststore)
create_ca "$OTHER_CA_STORE" "$OTHER_CA_DN" 2048 365 "$WORK_DIR/other-ca.crt"
issue_cert "order-service" "$NEG_DIR/untrusted-order-service/untrusted-order-service.p12" \
           "$WORK_DIR/untrusted-order-service.crt" "$OTHER_CA_STORE" \
           "$WORK_DIR/other-ca.crt" 365 "-1d"

print_summary