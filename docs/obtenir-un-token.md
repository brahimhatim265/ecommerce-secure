# Obtenir un token (étape 3)

Le serveur d'autorisation authentifie les personnes via `user-service`. Il faut donc démarrer les deux.

## Prérequis et ordre de démarrage

1. **`user-service`** (port 8081) : `UserApplication` dans IntelliJ, ou `./mvnw -pl user-service spring-boot:run`.
2. **`authorization-server`** (port 9000) : `AuthorizationServerApplication`, ou `./mvnw -pl authorization-server spring-boot:run`.

(`scripts/start-all.sh` ou `scripts\start-all.bat` lancent les services dans cet ordre.)

Vérification : `http://localhost:9000/.well-known/openid-configuration` doit répondre.

## Comptes de démonstration

Mot de passe commun : **`Demo@2026`**. Dans le formulaire de connexion, on saisit l'**email** dans le champ « Username ».

| Email | Rôle | État |
|---|---|---|
| `hamza.belhaj@example.com` | CUSTOMER | actif (UUID `00000000-0000-4000-8000-000000000001`) |
| `issam.bourhim@example.com`, `nizar.beniad@example.com`, `saad.hada@example.com`, `mehdi.fadli@example.com`, `brahim.hatim@example.com`, `louai.idrissi@example.com` | CUSTOMER | actifs |
| `admin@example.com` | ADMIN | actif |
| `anas.tazi@example.com` | CUSTOMER | **désactivé** |

## Méthode A : Postman

Dans l'onglet *Authorization* d'une requête : type **OAuth 2.0**, puis *Configure New Token* :

| Champ | Valeur |
|---|---|
| Grant Type | Authorization Code (With PKCE) |
| Callback URL | `https://oauth.pstmn.io/v1/callback` |
| Auth URL | `http://localhost:9000/oauth2/authorize` |
| Access Token URL | `http://localhost:9000/oauth2/token` |
| Client ID | `ecommerce-web` |
| Client Secret | vide (client public) |
| Code Challenge Method | SHA-256 |
| Scope | `openid profile email` |

Cliquer sur *Get New Access Token*, se connecter (email + `Demo@2026`) : Postman affiche le token. Les intitulés peuvent varier un peu selon la version de Postman.

## Méthode B : navigateur et `curl` (Git Bash, Linux ou macOS)

1. Générer le couple PKCE :
```bash
VERIFIER=$(openssl rand -base64 48 | tr -d '=+/\n' | cut -c1-64)
CHALLENGE=$(printf %s "$VERIFIER" | openssl dgst -sha256 -binary | openssl base64 -A | tr '+/' '-_' | tr -d '=')
```
2. Ouvrir dans le navigateur (afficher la valeur avec `echo $CHALLENGE`, la coller à la place de `<CHALLENGE>`) :
```
http://localhost:9000/oauth2/authorize?response_type=code&client_id=ecommerce-web&redirect_uri=http://127.0.0.1:8080/authorized&scope=openid%20profile%20email&state=abc&code_challenge=<CHALLENGE>&code_challenge_method=S256
```
3. Se connecter. Le navigateur est redirigé vers `http://127.0.0.1:8080/authorized?code=...&state=abc`. Rien n'écoute sur le port 8080 : une page d'erreur est normale, il suffit de **copier la valeur de `code`** dans la barre d'adresse.
4. Échanger le code contre le token (le code est à usage unique et expire vite) :
```bash
curl -s -X POST http://localhost:9000/oauth2/token \
  -d grant_type=authorization_code -d code=<CODE> \
  -d redirect_uri=http://127.0.0.1:8080/authorized \
  -d client_id=ecommerce-web -d code_verifier=$VERIFIER
```
5. Décoder le contenu de l'`access_token` (sans l'envoyer à un site tiers) :
```bash
echo "<ACCESS_TOKEN>" | cut -d. -f2 | tr '_-' '/+' | base64 -d 2>/dev/null
```
Le `sub` doit valoir l'UUID de la personne connectée (ex. `00000000-0000-4000-8000-000000000001`), `iss` vaut `http://localhost:9000`, et `exp - iat` fait 900 secondes. Un message d'erreur de padding en fin de sortie est normal.

## Scénarios et résultats attendus

| Scénario | Saisie | Résultat attendu |
|---|---|---|
| Connexion valide | `hamza.belhaj@example.com` / `Demo@2026` | Redirection vers `redirect_uri` avec un `code` ; le token a `sub` = UUID de Hamza |
| Mauvais mot de passe | `hamza.belhaj@example.com` / `faux` | Retour à la page de connexion en erreur (« Invalid credentials »), aucun code |
| Email inconnu | `inconnu@example.com` / `faux` | **Même page et même message** que le cas précédent, aucun code |
| Compte désactivé | `anas.tazi@example.com` / `Demo@2026` | Retour à la page de connexion en erreur, message générique sans détail, aucun token |
| `user-service` arrêté | n'importe quel compte | Retour à la page de connexion en erreur, message générique ; `authorization-server` reste actif ; après redémarrage de `user-service`, un nouvel essai réussit |

## Problèmes fréquents

- `invalid_grant` : le code a déjà été utilisé, a expiré, ou le `code_verifier` ne correspond pas au `code_challenge`.
- Erreur sur la redirection : `redirect_uri` doit être **exactement** `http://127.0.0.1:8080/authorized` (avec `127.0.0.1`, pas `localhost`).
- Token refusé après un redémarrage du serveur d'autorisation : les clés sont en mémoire, il faut redemander un token.