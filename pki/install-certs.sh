#!/usr/bin/env bash
# =============================================================================
# install-certs.sh : installe les certificats de la PKI dans les 4 modules
#                    (etape 7 : HTTPS et mTLS)
#
# Copie, depuis pki/generated/ :
#   <module>/src/main/resources/certs/keystore.p12     keystore du service
#   <module>/src/main/resources/certs/truststore.p12   truststore commun (la CA seule)
#   <module>/src/test/resources/certs/<nom>.p12         fixtures de test (dont les
#                                                       keystores "negatifs")
#
# Usage :   ./pki/install-certs.sh [--force]
#   --force   ecrase les fichiers existants dont le contenu est different
#             (sans cette option, un fichier different n'est JAMAIS ecrase)
#
# Comportement pour chaque fichier :
#   copie       le fichier n'existait pas : il est copie
#   identique   le fichier existe avec le meme contenu : rien a faire
#   DIFFERENT   le fichier existe avec un contenu different : non ecrase
#               (le script se termine alors avec le code 1)
#   ecrase      remplace grace a --force
#
# Securite : seuls les fichiers de pki/generated/ sont lus. La cle privee de la
# CA (pki/ca-private/) n'est jamais lue ni copiee.
#
# Apres une regeneration de la PKI (./pki/generate-pki.sh --force), relancez ce
# script avec --force pour mettre les modules a jour. Les certificats de
# demonstration sont valables 365 jours.
#
# Les messages sont volontairement en ASCII (sans accents), comme generate-pki.sh.
# =============================================================================
set -euo pipefail

# --- Chemins (tous deduits de l'emplacement du script) ------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
GEN_DIR="$SCRIPT_DIR/generated"
NEG_DIR="$GEN_DIR/negative-tests"
TRUSTSTORE="$GEN_DIR/truststore.p12"

usage() {
  echo "Usage : $0 [--force]"
  echo "  --force   ecrase les fichiers existants dont le contenu est different"
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

CONFLICTS=0
ERRORS=0

# --- Sources : chemin du keystore d'un service ou d'un certificat negatif -----
service_store()  { echo "$GEN_DIR/$1/$1.p12"; }
negative_store() { echo "$NEG_DIR/$1/$1.p12"; }

# --- install_file SOURCE MODULE main|test NOM_DESTINATION ---------------------
install_file() {
  local src="$1" module="$2" kind="$3" name="$4"
  local rel="$module/src/$kind/resources/certs"
  local dst_dir="$ROOT_DIR/$rel"
  local dst="$dst_dir/$name"

  if [ ! -f "$src" ]; then
    echo "  ERREUR      : source introuvable : ${src#"$ROOT_DIR"/}" >&2
    echo "                (lancez d'abord ./pki/generate-pki.sh)" >&2
    ERRORS=$((ERRORS + 1))
    return 0
  fi

  mkdir -p "$dst_dir"

  if [ ! -e "$dst" ]; then
    cp "$src" "$dst"
    echo "  copie       : $rel/$name"
  elif cmp -s "$src" "$dst"; then
    echo "  identique   : $rel/$name"
  elif [ "$FORCE" -eq 1 ]; then
    cp "$src" "$dst"
    echo "  ecrase      : $rel/$name"
  else
    echo "  DIFFERENT   : $rel/$name (non ecrase, utilisez --force)" >&2
    CONFLICTS=$((CONFLICTS + 1))
  fi
}

# --- install_main MODULE : keystore du service + truststore commun ------------
install_main() {
  install_file "$(service_store "$1")" "$1" main keystore.p12
  install_file "$TRUSTSTORE"           "$1" main truststore.p12
}

# --- install_test MODULE SOURCE... : fixtures de test, nom d'origine conserve --
install_test() {
  local module="$1" src
  shift
  for src in "$@"; do
    install_file "$src" "$module" test "$(basename "$src")"
  done
}

# --- Execution ----------------------------------------------------------------
echo "Installation des certificats (force=$FORCE)"

for module in user-service order-service payment-service authorization-server; do
  echo "[$module] main"
  install_main "$module"
done

# Fixtures de test : ce dont chaque module a besoin pour ses tests TLS.
# user-service et payment-service jouent le serveur : le client de test presente
# le certificat d'order-service, d'authorization-server ou un certificat negatif.
echo "[user-service] test"
install_test user-service \
  "$(service_store order-service)" "$(service_store authorization-server)" \
  "$(negative_store rogue-service)" "$(negative_store expired-order-service)" \
  "$(negative_store untrusted-order-service)"

echo "[payment-service] test"
install_test payment-service \
  "$(service_store order-service)" "$(service_store authorization-server)" \
  "$(negative_store rogue-service)" "$(negative_store expired-order-service)" \
  "$(negative_store untrusted-order-service)"

# order-service et authorization-server jouent le client : un petit serveur HTTPS
# de test presente le certificat du service vise (ou un certificat non fiable).
echo "[order-service] test"
install_test order-service \
  "$(service_store user-service)" "$(service_store payment-service)" \
  "$(service_store authorization-server)" "$(negative_store untrusted-order-service)"

echo "[authorization-server] test"
install_test authorization-server \
  "$(service_store user-service)" "$(negative_store untrusted-order-service)"

# --- Bilan --------------------------------------------------------------------
if [ "$ERRORS" -gt 0 ]; then
  echo "ECHEC : $ERRORS source(s) introuvable(s)." >&2
  exit 1
fi
if [ "$CONFLICTS" -gt 0 ]; then
  echo "ATTENTION : $CONFLICTS fichier(s) different(s) non ecrase(s)." >&2
  echo "            Relancez avec --force pour les remplacer." >&2
  exit 1
fi
echo "Termine : les certificats sont installes."