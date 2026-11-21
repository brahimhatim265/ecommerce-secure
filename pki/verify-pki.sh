#!/usr/bin/env bash
# =============================================================================
# verify-pki.sh : controle objectif des artefacts de pki/generated
# Enveloppe mince autour de VerifyPki.java (JDK 17 ou plus, aucune dependance).
#
# Usage :   ./pki/verify-pki.sh
# Variable : PKI_PASSWORD (mot de passe des keystores ; demonstration si absent)
# Code de sortie : 0 = tous les controles PASS, 1 = au moins un FAIL,
#                  2 = dossier pki/generated introuvable
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if ! command -v java >/dev/null 2>&1; then
  echo "ERREUR : java est introuvable. Installez un JDK 17 ou plus et" >&2
  echo "         verifiez que JAVA_HOME/bin est dans le PATH." >&2
  exit 1
fi

exec java "$SCRIPT_DIR/VerifyPki.java" "$SCRIPT_DIR"