# ecommerce-secure — boutique en ligne d'électronique (Maroc), 3 microservices

Trois services Spring Boot indépendants, plus un serveur d'autorisation OAuth 2.1 / OIDC. Depuis l'étape 5,
**chaque service valide lui-même les tokens JWT** ; depuis l'étape 7, **toutes les communications sont en HTTPS** et les services s'identifient entre eux par certificat (mTLS).
Tous les montants sont en **MAD** (dirham marocain).

> 🔐 **Sécurité (étapes 5 à 7)** : tous les endpoints exigent un token d'accès JWT, sauf quelques URL publiques
> (voir [docs/validation-jwt-services.md](docs/validation-jwt-services.md)). Les **règles d'autorisation par rôle
> ne sont pas encore appliquées** (étape suivante) : un token valide ouvre toutes les URL protégées. `ownerId` est
> encore lu dans le corps des requêtes. Depuis l'étape 7, les services écoutent en **HTTPS uniquement** et deux
> endpoints sont **réservés à un service précis** par son certificat client (mTLS) : `POST /internal/auth/verify`
> (`authorization-server`) et `POST /payments` (`order-service`, avec le JWT de l'utilisateur relayé). Voir
> [docs/mtls-services.md](docs/mtls-services.md).

## 1. Architecture

| Service | Port | Rôle | Base H2 (en mémoire) |
|---|---|---|---|
| `user-service` | 8081 | comptes, profils, mots de passe BCrypt, rôles, vérification d'identifiants | `usersdb` |
| `order-service` | 8082 | catalogue produits, commandes, **orchestration** du flux de commande | `ordersdb` |
| `payment-service` | 8083 | simulation d'un prestataire de paiement (mode *sandbox*), idempotence | `paymentsdb` |

```
Client ──► order-service ──GET /users/{id}──────────────► user-service
                │
                └────────POST /payments (Idempotency-Key)► payment-service
```

Chaque service a sa propre base ; **aucune jointure ni clé étrangère entre services** (seulement des identifiants :
UUID d'utilisateur, id de commande, id de paiement). Couches strictes : `controller → service → repository`, DTO
séparés des entités, mappers écrits à la main, format d'erreur unique (voir §6).

Flux de création de commande (`POST /orders`) — aucun appel HTTP n'est fait dans une transaction base de données :

1. validation du corps (400) ; lignes de produits dupliquées → 400 ;
2. `GET /users/{ownerId}` sur user-service (404 → 404 ; inactif → 422 ; injoignable → 503) ;
3. **transaction A** : contrôle des produits/stock, réservation du stock, total calculé depuis les prix courants,
   commande `PENDING_PAYMENT` avec copie du nom et du prix des produits ;
4. `POST /payments` avec `Idempotency-Key: order-{id}` (1 nouvelle tentative **uniquement** si aucune réponse HTTP
   reçue : erreur d'E/S ou délai dépassé ; timeouts 2 s connexion / 5 s lecture) ;
5. **transaction B** : `CONFIRMED` (carte `SUCCEEDED` ou livraison `PENDING`), `PAYMENT_REFUSED` (carte refusée,
   stock restitué), ou `PAYMENT_ERROR` (échec technique ou requête de paiement invalide, stock restitué).

Une carte refusée est un **résultat métier normal** : `201` avec `status = PAYMENT_REFUSED`.

## 2. Prérequis et lancement

* JDK 17 ou plus récent (le projet vise Java 17) ; Maven est fourni par le wrapper (`./mvnw`, `mvnw.cmd`).
* Spring Boot **3.5.16** (dernière version de la branche 3.x), springdoc-openapi 2.8.13.
* **Certificats (étape 7)** : avant le premier lancement, installer ceux de la PKI dans les modules, avec
  `pki\install-certs.bat` (Windows) ou `./pki/install-certs.sh`. Les quatre services écoutent en **HTTPS** (8081, 8082,
  8083, 9000) : le navigateur et Postman doivent faire confiance à `pki/generated/ca.crt`, et `curl` s'utilise avec
  `--cacert pki/generated/ca.crt` (sous PowerShell : `curl.exe --ssl-no-revoke --cacert pki/generated/ca.crt`).
  Détails dans [docs/mtls-services.md](docs/mtls-services.md).

```bash
./mvnw clean verify                      # compile + tous les tests des 3 modules
./scripts/start-all.sh                   # Linux/macOS : lance user → authorization → payment → order
scripts\start-all.bat                    # Windows
# ou service par service, dans 3 terminaux :
./mvnw -pl user-service spring-boot:run
./mvnw -pl payment-service spring-boot:run
./mvnw -pl order-service spring-boot:run
```

Santé : `/actuator/health` (public).

**Ordre de démarrage recommandé** (celui des scripts) : `user-service`, `authorization-server`, `payment-service`,
`order-service`. Un service démarre même si le serveur d'autorisation est arrêté, mais ce dernier doit être prêt
avant la première requête authentifiée, et `user-service` avant la première connexion.

**Swagger est désactivé par défaut** (les URL répondent 401). Pour l'activer, lancer les services avec le profil
`demo` : `set SPRING_PROFILES_ACTIVE=demo` avant `scripts\start-all.bat` (Windows), ou
`SPRING_PROFILES_ACTIVE=demo ./scripts/start-all.sh` (Git Bash, Linux, macOS), ou
`./mvnw -pl user-service spring-boot:run "-Dspring-boot.run.profiles=demo"` pour un seul service. Swagger UI est alors
public : <https://localhost:8081/swagger-ui.html>, `:8082`, `:8083` (les adresses affichées à la fin de `start-all.*`
ne fonctionnent qu'avec ce profil). Le bouton **Authorize** accepte un access token pour tester les URL protégées.
Détails dans [docs/validation-jwt-services.md](docs/validation-jwt-services.md).

Variables d'environnement utiles : `USER_SERVICE_URL`, `PAYMENT_SERVICE_URL`, `HTTP_CONNECT_TIMEOUT`,
`HTTP_READ_TIMEOUT`, `SEED_DEFAULT_PASSWORD`, `PAYMENT_RANDOM_SEED`, `PAYMENT_WEIGHT_SUCCESS`,
`PAYMENT_WEIGHT_INSUFFICIENT_FUNDS`, `PAYMENT_WEIGHT_CARD_DECLINED`, `PAYMENT_WEIGHT_EXPIRED_CARD`.
Pour le TLS : `PKI_PASSWORD`, `PKI_TRUSTSTORE_LOCATION`, `<SERVICE>_KEYSTORE_LOCATION` (par exemple
`USER_SERVICE_KEYSTORE_LOCATION`), `AUTH_ISSUER_URI` (défaut `https://localhost:9000`).

## 3. Données de démonstration

**Mot de passe de démonstration commun : `Demo@2026`** (propriété `app.seed.default-password`).
À ne **jamais** utiliser hors démonstration.

| UUID fixe | Prénom Nom | Email | Ville | Rôle | Actif |
|---|---|---|---|---|---|
| `00000000-0000-4000-8000-000000000001` | Hamza Belhaj | hamza.belhaj@example.com | Casablanca | CUSTOMER | oui |
| `…0002` | Issam Bourhim | issam.bourhim@example.com | Rabat | CUSTOMER | oui |
| `…0003` | Nizar Beniad | nizar.beniad@example.com | Marrakech | CUSTOMER | oui |
| `…0004` | Saad Hada | saad.hada@example.com | Tanger | CUSTOMER | oui |
| `…0005` | Mehdi Fadli | mehdi.fadli@example.com | Fès | CUSTOMER | oui |
| `…0006` | Brahim Hatim | brahim.hatim@example.com | Agadir | CUSTOMER | oui |
| `…0007` | Louai Idrissi | louai.idrissi@example.com | Salé | CUSTOMER | oui |
| `…0008` | Administrateur Boutique | admin@example.com | Casablanca | ADMIN | oui |
| `…0009` | Anas Tazi | anas.tazi@example.com | Oujda | CUSTOMER | **non** |

(UUID complets : `00000000-0000-4000-8000-00000000000N`.)

**Produits** (id 1 à 9, stocks *courants* après les 5 commandes de démo) : PS5 Slim 5 990 (18) · DualSense 790 (59) ·
iPhone 18 13 990 (14) · Galaxy S25 Ultra 12 990 (20) · TV LG 55" 5 490 (11) · MacBook Air 13" M4 12 490 (8) ·
AirPods Pro 2 2 490 (40) · Sony WH-1000XM5 3 290 (24) · Chargeur USB-C 65 W 249 (150).

**Commandes 1 à 5** (`CONFIRMED`, `paymentId` = id de commande) : 6 780 · 13 990 · 5 490 · 3 539 (cartes) ·
2 988 (livraison). **Paiements 1 à 5** : mêmes montants ; Visa 4242, Mastercard 4444, dernier en `PENDING`.
Les dates sont réparties sur les 5 derniers jours (même formule dans les deux services).

## 4. Jetons de carte de test (mode *sandbox*)

Aucun vrai numéro de carte n'est jamais manipulé : le client envoie un **jeton public** dont le nom décide du résultat.

| `cardToken` | Résultat | Carte |
|---|---|---|
| `tok_visa_approved` | `SUCCEEDED` | VISA ····4242 |
| `tok_mastercard_approved` | `SUCCEEDED` | MASTERCARD ····4444 |
| `tok_declined_insufficient_funds` | `FAILED` / `INSUFFICIENT_FUNDS` | VISA ····9995 |
| `tok_declined_expired_card` | `FAILED` / `EXPIRED_CARD` | VISA ····0069 |
| `tok_declined_generic` | `FAILED` / `CARD_DECLINED` | VISA ····0002 |
| `tok_random` | tirage pondéré, **une seule fois** (rejouer la même clé ne retire pas) | selon le tirage |

Poids par défaut de `tok_random` : succès 60 (Visa ou Mastercard, équiprobables) · fonds insuffisants 20 ·
refus générique 10 · carte expirée 10. Réglage dans `payment-service/src/main/resources/application.yml`
(`payment.simulation.random`), poids entiers ≥ 0, somme > 0, **normalisés** (pas besoin de totaliser 100) ;
valeurs invalides = **échec au démarrage**. `seed` optionnel pour des tirages reproductibles.
Un jeton absent du catalogue → `400 "Unknown card token"`. Livraison : `CASH_ON_DELIVERY` sans jeton → `PENDING`.

## 5. API et scénarios `curl`

Les services sont en **HTTPS**. Variables et réglage de `curl` (Git Bash, Linux, macOS ; le `curl.exe` de Windows
ne sait pas lire un certificat client `.p12`, voir [docs/mtls-services.md](docs/mtls-services.md)) :

```bash
U=https://localhost:8081  O=https://localhost:8082  P=https://localhost:8083
H=00000000-0000-4000-8000-000000000001                         # Hamza
alias curl='curl --cacert pki/generated/ca.crt'                # faire confiance à la CA du projet
AUTHCERT="--cert pki/generated/authorization-server/authorization-server.p12:ecommerce-demo-pki --cert-type P12"
ORDERCERT="--cert pki/generated/order-service/order-service.p12:ecommerce-demo-pki --cert-type P12"
```

Depuis l'étape 5, l'API exige aussi un token : les exemples ci-dessous omettent `-H "Authorization: Bearer $TOKEN"`
(voir [docs/obtenir-un-token.md](docs/obtenir-un-token.md)), sauf pour `POST /payments` où il est indispensable.

### user-service
```bash
curl -s $U/users/$H                                              # 200
curl -s "$U/users?email=Hamza.Belhaj@Example.com"                # 200, liste de 1 (insensible à la casse)
curl -s -X POST $U/users -H 'Content-Type: application/json' -d '{
  "email":"Karim.Alaoui@Example.com","password":"Secret123","firstName":"Karim","lastName":"Alaoui","city":"Casablanca"}'
                                                                 # 201 + Location, rôle CUSTOMER, email en minuscules
# même email (autre casse) -> 409 ; champ "roles" dans le corps -> 400 ; mot de passe sans chiffre -> 400
curl -s -X PUT $U/users/$H -H 'Content-Type: application/json' -d '{"firstName":"Hamza","lastName":"Belhaj","city":"Rabat"}'   # 200
curl -s -X PUT $U/users/$H -H 'Content-Type: application/json' -d '{"firstName":"H","lastName":"B","email":"x@y.com"}'          # 400 (email immuable)
curl -s -X PATCH $U/users/$H/status -H 'Content-Type: application/json' -d '{"active":false}'                                  # 200
# vérification d'identifiants (interne) : réservée à authorization-server (certificat client) ; sans certificat -> 401, avec celui d'un autre service -> 403
curl -s $AUTHCERT -X POST $U/internal/auth/verify -H 'Content-Type: application/json' -d '{"email":"hamza.belhaj@example.com","password":"Demo@2026"}'  # 200 (jamais de hash)
curl -s $AUTHCERT -X POST $U/internal/auth/verify -H 'Content-Type: application/json' -d '{"email":"hamza.belhaj@example.com","password":"faux"}'      # 401 "Invalid credentials"
curl -s $AUTHCERT -X POST $U/internal/auth/verify -H 'Content-Type: application/json' -d '{"email":"inconnu@example.com","password":"faux"}'          # 401, même message
curl -s $AUTHCERT -X POST $U/internal/auth/verify -H 'Content-Type: application/json' -d '{"email":"anas.tazi@example.com","password":"Demo@2026"}'   # 403 "Account is disabled"
```

### order-service
```bash
curl -s $O/products ; curl -s "$O/products?category=gaming" ; curl -s $O/products/1
curl -s -X POST $O/products -H 'Content-Type: application/json' -d '{"sku":"NEW-1","name":"Nouveau","brand":"Marque","category":"Gaming","price":99.90,"stock":5}'   # 201 ; SKU/nom déjà pris -> 409

# 1. commande payée par carte -> 201, status CONFIRMED, stock décrémenté
curl -s -X POST $O/orders -H 'Content-Type: application/json' -d '{
  "ownerId":"'$H'","shippingAddress":"45 Rue Ibnou Katir, Maârif","shippingCity":"Casablanca",
  "paymentMethod":"CARD","cardToken":"tok_visa_approved",
  "items":[{"productId":1,"quantity":1},{"productId":2,"quantity":1}]}'
# 2. carte refusée -> 201, status PAYMENT_REFUSED, paymentFailureReason INSUFFICIENT_FUNDS, stock restitué
#    (même corps avec "cardToken":"tok_declined_insufficient_funds")
# 3. livraison -> 201, status CONFIRMED, paiement PENDING (même corps avec "paymentMethod":"CASH_ON_DELIVERY" et sans cardToken)
# 4. stock insuffisant (productId 6, quantity 9) -> 422 ; produit inconnu -> 404 ; ownerId inconnu -> 404 ; compte inactif (…0009) -> 422
# 5. CARD sans cardToken, ou CASH_ON_DELIVERY avec cardToken -> 400 avec explication dans "details"
# 6. jeton inconnu ("tok_nope") -> 400 "Unknown card token", commande PAYMENT_ERROR, stock restitué
curl -s $O/orders/6 ; curl -s "$O/orders?ownerId=$H"
```

### payment-service
```bash
KEY=demo-$(date +%s)   # POST /payments : certificat d'order-service ET token ; sans certificat -> 401, autre service -> 403
curl -s -i $ORDERCERT -H "Authorization: Bearer $TOKEN" -X POST $P/payments -H "Idempotency-Key: $KEY" -H 'Content-Type: application/json' -d '{
  "orderId":9001,"ownerId":"'$H'","amount":6780.00,"currency":"MAD","method":"CARD","cardToken":"tok_random"}'
# -> 201 ; rejouer EXACTEMENT la même commande -> 200 + en-tête "Idempotent-Replayed: true", même paiement, pas de nouveau tirage
# même clé avec un autre montant -> 409 ; autre clé pour la même commande -> 409 ; en-tête Idempotency-Key absent -> 400
curl -s $P/payments/1 ; curl -s $P/payments/order/1 ; curl -s "$P/payments?ownerId=$H"
curl -s -X POST $P/payments/5/collect        # livraison PENDING -> SUCCEEDED (200) ; deuxième appel ou paiement par carte -> 409
```
Il n'existe pas de remboursement ni de suppression (suppression d'utilisateur, de commande ou de paiement : non prévue).

### Panne simulée
Arrêter `payment-service`, puis `POST /orders` : réponse **503** avec `details: ["orderId: N"]`, commande en
`PAYMENT_ERROR`, stock restitué. Arrêter `user-service` : **503** sans aucune réservation de stock.

## 6. Format d'erreur unique (les 3 services)

```json
{"timestamp":"2026-11-14T10:15:30.123Z","status":409,"error":"Conflict","message":"Email already in use","path":"/users","details":[]}
```
`details` liste les erreurs de validation (`champ: message`). Jamais de trace, de nom de classe ou de SQL côté client
(les erreurs inattendues donnent `500 "Internal server error"` et sont journalisées côté serveur).
Codes : 400 validation / JSON illisible / propriété inconnue / UUID malformé · 401 · 403 · 404 · 405 · 409 conflit
(doublon, clé d'idempotence, verrou optimiste) · 422 règle métier · 503 service distant indisponible.

## 7. Choix de conception notables

* **Concurrence sur le stock** : verrouillage optimiste (`@Version` sur `Product`) ; un conflit donne `409`.
  La restitution du stock est rejouée jusqu'à 3 fois en cas de collision.
* **Idempotence** : clé unique + empreinte SHA-256 des paramètres ; le jeton de carte n'est **jamais** stocké
  (seulement marque, 4 derniers chiffres, résultat). Contraintes d'unicité en base (`orderId`, clé, référence) ;
  une collision concurrente est résolue en relisant la ligne gagnante.
* **Mots de passe** : BCrypt, jamais renvoyés ni
  journalisés ; `toString()` des requêtes les masque ; emails masqués dans les logs (`h***@example.com`).
  Compte inconnu → comparaison BCrypt factice (même durée, même message que mot de passe erroné).
* Jackson **refuse les propriétés inconnues** ; `Clock` injecté ; `open-in-view: false` ; H2 `create-drop`.

## 8. Préparation à la sécurité (étapes suivantes)

* **Étape 5, validation des JWT (en place)** : chaque service est un *resource server* Spring Security. Il refuse
  (401, format `ApiError`) tout appel sans token valide (signature, émetteur, expiration, claim `roles`), sauf les URL
  publiques (`POST /users`, `GET /products…`, `/actuator/health`) et `POST /internal/auth/verify`, qui n'exige pas de token mais le certificat d'`authorization-server` (étape 7).
  Réponse 503 si le serveur d'autorisation est injoignable. Voir [docs/validation-jwt-services.md](docs/validation-jwt-services.md)
  (fonctionnement, configuration, dépannage, limites connues).
* `ownerId` : lu dans `OrderController.create` puis passé explicitement à `OrderService.createOrder(ownerId, …)` —
  **un seul endroit** à remplacer par le claim `sub` du JWT ; aucun email/nom n'est copié hors de user-service.
* **Étape 7, HTTPS et mTLS (en place)** : les quatre modules n'écoutent plus qu'en HTTPS (`client-auth: want`) ;
  `POST /internal/auth/verify` est réservé à `authorization-server` et `POST /payments` à `order-service` (et exige le
  JWT relayé) ; les appels sortants présentent le certificat du service ; l'issuer devient `https://localhost:9000`.
  Fonctionnement, commandes `curl`, limites connues et dépannage : [docs/mtls-services.md](docs/mtls-services.md).
* `RestClientConfig` est l'**unique** point de création des clients HTTP (URL, timeouts, relais du token par
  `BearerTokenRelayInterceptor`, certificat client du SSL bundle `mtls`). Les rôles (`CUSTOMER`, `ADMIN`) sont dans le claim `roles`
  du token ; leur application relève de l'étape suivante.
* Nouveau module : ajouter un `<module>` au `pom.xml` parent ; les versions sont centralisées (`dependencyManagement`).

## Certificats (PKI de démonstration, étape 6)

Une autorité de certification interne et un certificat par service (`user-service`, `order-service`, `payment-service`, `authorization-server`) sont générés avec `keytool` ; l'**étape 7** les utilise pour le HTTPS et le mTLS des services. Les artefacts de `pki/generated/` sont versionnés (démonstration) ; la clé privée de la CA reste dans `pki/ca-private/`, exclu du dépôt.

| | Windows | Linux / macOS / Git Bash |
|---|---|---|
| Générer | `pki\generate-pki.bat` | `./pki/generate-pki.sh` |
| Vérifier | `pki\verify-pki.bat` | `./pki/verify-pki.sh` |
| Installer dans les modules | `pki\install-certs.bat` | `./pki/install-certs.sh` |

Schéma de la PKI, glossaire, commandes `keytool`, certificats « négatifs », régénération, limites connues et dépannage : [docs/pki-certificats.md](docs/pki-certificats.md).

## Module `authorization-server` (étapes 1 à 4)

Serveur d'autorisation OAuth 2.0 / OpenID Connect (Spring Authorization Server) qui émet les jetons JWT du projet. Il démarre sur le port **9000** et son émetteur (issuer) est fixé à `https://localhost:9000` (HTTPS depuis l'étape 7).

Lancement : `user-service` doit déjà tourner (port 8081). Ensuite, exécuter `AuthorizationServerApplication` depuis IntelliJ, ou `./mvnw -pl authorization-server spring-boot:run` (sous Windows : `mvnw.cmd -pl authorization-server spring-boot:run`). Ou lancer `scripts/start-all.*`, qui respecte l'ordre.

### Authentification (étape 3)

Ce serveur n'a **aucune base d'utilisateurs**. À la page de connexion, la personne saisit son **email** (dans le champ « Username », nom imposé par Spring Security) et son mot de passe. Le serveur les transmet à `user-service` (`POST /internal/auth/verify`), qui reste seul à détenir les mots de passe hachés : le hash ne quitte jamais `user-service`. L'adresse de `user-service` se règle avec la variable `USER_SERVICE_URL` (défaut `https://localhost:8081`).

| Réponse de `user-service` | Résultat de la connexion |
|---|---|
| 200 | Connexion réussie, le flux Authorization Code continue |
| 401 (mauvais mot de passe ou email inconnu) | Retour à la page de connexion, message générique identique dans les deux cas |
| 403 (compte désactivé) | Retour à la page de connexion, message générique, aucun token |
| Panne (délai dépassé, service arrêté, erreur 5xx, réponse illisible) | Retour à la page de connexion, message générique, aucun token ; le serveur reste actif |

La connexion **échoue fermée** : au moindre doute, aucun token n'est émis. Les échecs sont journalisés en `WARN` avec l'email masqué (`h***@example.com`) et la raison interne, jamais le mot de passe. Le `sub` du token est l'**UUID** de l'utilisateur.

### URL utiles

- `https://localhost:9000/.well-known/openid-configuration` : document de découverte OIDC ;
- `https://localhost:9000/oauth2/jwks` : clés publiques de signature (aucune clé privée n'est exposée) ;
- `https://localhost:9000/oauth2/authorize` et `https://localhost:9000/oauth2/token` : endpoints du flux Authorization Code.

### Client public enregistré

| Paramètre | Valeur |
|---|---|
| `client_id` | `ecommerce-web` (client public, aucun secret) |
| Flux | Authorization Code **avec PKCE obligatoire** (`S256`) |
| Scopes | `openid`, `profile`, `email` |
| URI de redirection | `http://127.0.0.1:8080/authorized` et `https://oauth.pstmn.io/v1/callback` |
| Durée de l'access token | 15 minutes |
| Consentement | désactivé (application de première partie) |

Pour obtenir un token pas à pas (Postman, ou navigateur et `curl`), voir [docs/obtenir-un-token.md](docs/obtenir-un-token.md).

### Contenu des tokens (étape 4)

Le serveur enrichit les tokens à partir de l'utilisateur authentifié, sans appel supplémentaire à `user-service`. Ce format est un **contrat** avec l'étape 5 (détail, exemples et limites dans [docs/contrat-jwt.md](docs/contrat-jwt.md)) :

| Claim | Valeur | Access token | ID token |
|---|---|---|---|
| `sub` | UUID de l'utilisateur | oui | oui |
| `email` | adresse, si le scope `email` est accordé | oui | oui |
| `name` | prénom et nom, si le scope `profile` est accordé | oui | oui |
| `roles` | tableau sans préfixe `ROLE_` (ex. `["CUSTOMER"]`), trié, toujours présent | oui | non |

Aucune donnée sensible (mot de passe, hash, téléphone, adresse, état du compte) n'est mise dans les tokens, et `email_verified` est volontairement absent. Les rôles sont figés dans le token jusqu'à son expiration (15 minutes). Le point de terminaison `GET /userinfo` (avec l'access token) renvoie `sub`, `email` et `name`. Pour vérifier les claims, voir [docs/obtenir-un-token.md](docs/obtenir-un-token.md).

### Limites connues

- **`/internal/auth/verify` n'est protégé que par le certificat de service** (mTLS, étape 7) : pas de révocation, pas de limitation des tentatives. Limites détaillées dans [docs/mtls-services.md](docs/mtls-services.md).
- Pas de limitation des tentatives de connexion.
- La disponibilité du serveur d'autorisation dépend de `user-service` : s'il est arrêté, plus personne ne peut se connecter (le serveur, lui, reste actif).
- Les clés de signature sont générées **en mémoire** au démarrage : elles changent à chaque redémarrage, ce qui invalide tous les jetons déjà émis.
- Aucun refresh token n'est émis (client public) ; l'utilisateur doit se reconnecter après l'expiration du token.
- Les tokens sont signés mais **non chiffrés** : leur contenu est lisible par tout détenteur. Un changement de rôle dans `user-service` n'est pris en compte qu'à la prochaine connexion.

## 9. Arborescence

```
ecommerce-secure/  pom.xml  mvnw  mvnw.cmd  .mvn/  scripts/  README.md
├── user-service/     controller service repository entity dto mapper exception config security  (+ tests)
├── order-service/    … + client/ (UserClient, PaymentClient)
├── payment-service/  …
├── authorization-server/  serveur OAuth 2.1 / OIDC (config, client, security)
├── pki/              CA et certificats de démonstration, scripts generate / verify / install
└── docs/             contrat JWT, validation des JWT, PKI, mTLS, obtenir un token
```