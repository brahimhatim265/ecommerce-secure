# ecommerce-secure — boutique en ligne d'électronique (Maroc), 3 microservices

Socle **métier sans sécurité d'accès** : trois services Spring Boot indépendants, prêts à recevoir plus tard
un 4e module (serveur d'autorisation OAuth 2.1 / OIDC), de la sécurité JWT sur les APIs et du mTLS entre services.
Tous les montants sont en **MAD** (dirham marocain).

> ⚠️ **Aucune protection d'accès dans cette version** : tous les endpoints sont ouverts, `ownerId` est lu dans le
> corps des requêtes, `/internal/auth/verify` est public. C'est volontaire ; voir « Préparation à la sécurité ».

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

```bash
./mvnw clean verify                      # compile + tous les tests des 3 modules
./scripts/start-all.sh                   # Linux/macOS : lance user → authorization → payment → order
scripts\start-all.bat                    # Windows
# ou service par service, dans 3 terminaux :
./mvnw -pl user-service spring-boot:run
./mvnw -pl payment-service spring-boot:run
./mvnw -pl order-service spring-boot:run
```

Swagger UI : <http://localhost:8081/swagger-ui.html>, `:8082`, `:8083` · Santé : `/actuator/health`.

Variables d'environnement utiles : `USER_SERVICE_URL`, `PAYMENT_SERVICE_URL`, `HTTP_CONNECT_TIMEOUT`,
`HTTP_READ_TIMEOUT`, `SEED_DEFAULT_PASSWORD`, `PAYMENT_RANDOM_SEED`, `PAYMENT_WEIGHT_SUCCESS`,
`PAYMENT_WEIGHT_INSUFFICIENT_FUNDS`, `PAYMENT_WEIGHT_CARD_DECLINED`, `PAYMENT_WEIGHT_EXPIRED_CARD`.

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

Variables : `U=http://localhost:8081  O=http://localhost:8082  P=http://localhost:8083`
et `H=00000000-0000-4000-8000-000000000001` (Hamza).

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
# vérification d'identifiants (interne)
curl -s -X POST $U/internal/auth/verify -H 'Content-Type: application/json' -d '{"email":"hamza.belhaj@example.com","password":"Demo@2026"}'  # 200 (jamais de hash)
curl -s -X POST $U/internal/auth/verify -H 'Content-Type: application/json' -d '{"email":"hamza.belhaj@example.com","password":"faux"}'      # 401 "Invalid credentials"
curl -s -X POST $U/internal/auth/verify -H 'Content-Type: application/json' -d '{"email":"inconnu@example.com","password":"faux"}'          # 401, même message
curl -s -X POST $U/internal/auth/verify -H 'Content-Type: application/json' -d '{"email":"anas.tazi@example.com","password":"Demo@2026"}'   # 403 "Account is disabled"
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
KEY=demo-$(date +%s)
curl -s -i -X POST $P/payments -H "Idempotency-Key: $KEY" -H 'Content-Type: application/json' -d '{
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
* **Mots de passe** : BCrypt (`spring-security-crypto` uniquement, pas le starter de sécurité), jamais renvoyés ni
  journalisés ; `toString()` des requêtes les masque ; emails masqués dans les logs (`h***@example.com`).
  Compte inconnu → comparaison BCrypt factice (même durée, même message que mot de passe erroné).
* Jackson **refuse les propriétés inconnues** ; `Clock` injecté ; `open-in-view: false` ; H2 `create-drop`.

## 8. Préparation à la sécurité (étapes suivantes)

* `ownerId` : lu dans `OrderController.create` puis passé explicitement à `OrderService.createOrder(ownerId, …)` —
  **un seul endroit** à remplacer par le claim `sub` du JWT ; aucun email/nom n'est copié hors de user-service.
* `/internal/auth/verify` : réservé au futur serveur d'autorisation (à restreindre par mTLS / réseau).
* `RestClientConfig` est l'**unique** point de création des clients HTTP (URL, timeouts) : brancher ici mTLS et le
  relais de jeton. Les rôles (`CUSTOMER`, `ADMIN`) existent déjà dans l'identité vérifiée.
* Nouveau module : ajouter un `<module>` au `pom.xml` parent ; les versions sont centralisées (`dependencyManagement`).

## Module `authorization-server` (étapes 1 à 3)

Serveur d'autorisation OAuth 2.0 / OpenID Connect (Spring Authorization Server) qui émet les jetons JWT du projet. Il démarre sur le port **9000** et son émetteur (issuer) est fixé à `http://localhost:9000`.

Lancement : `user-service` doit déjà tourner (port 8081). Ensuite, exécuter `AuthorizationServerApplication` depuis IntelliJ, ou `./mvnw -pl authorization-server spring-boot:run` (sous Windows : `mvnw.cmd -pl authorization-server spring-boot:run`). Ou lancer `scripts/start-all.*`, qui respecte l'ordre.

### Authentification (étape 3)

Ce serveur n'a **aucune base d'utilisateurs**. À la page de connexion, la personne saisit son **email** (dans le champ « Username », nom imposé par Spring Security) et son mot de passe. Le serveur les transmet à `user-service` (`POST /internal/auth/verify`), qui reste seul à détenir les mots de passe hachés : le hash ne quitte jamais `user-service`. L'adresse de `user-service` se règle avec la variable `USER_SERVICE_URL` (défaut `http://localhost:8081`).

| Réponse de `user-service` | Résultat de la connexion |
|---|---|
| 200 | Connexion réussie, le flux Authorization Code continue |
| 401 (mauvais mot de passe ou email inconnu) | Retour à la page de connexion, message générique identique dans les deux cas |
| 403 (compte désactivé) | Retour à la page de connexion, message générique, aucun token |
| Panne (délai dépassé, service arrêté, erreur 5xx, réponse illisible) | Retour à la page de connexion, message générique, aucun token ; le serveur reste actif |

La connexion **échoue fermée** : au moindre doute, aucun token n'est émis. Les échecs sont journalisés en `WARN` avec l'email masqué (`h***@example.com`) et la raison interne, jamais le mot de passe. Le `sub` du token est l'**UUID** de l'utilisateur.

### URL utiles

- `http://localhost:9000/.well-known/openid-configuration` : document de découverte OIDC ;
- `http://localhost:9000/oauth2/jwks` : clés publiques de signature (aucune clé privée n'est exposée) ;
- `http://localhost:9000/oauth2/authorize` et `http://localhost:9000/oauth2/token` : endpoints du flux Authorization Code.

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

### Limites connues

- **L'appel vers `/internal/auth/verify` est en clair (HTTP) et non protégé** : n'importe quel client du réseau peut l'appeler. Il ne sera protégé que par le mTLS (étapes 6 et 7).
- Pas de limitation des tentatives de connexion.
- La disponibilité du serveur d'autorisation dépend de `user-service` : s'il est arrêté, plus personne ne peut se connecter (le serveur, lui, reste actif).
- Les clés de signature sont générées **en mémoire** au démarrage : elles changent à chaque redémarrage, ce qui invalide tous les jetons déjà émis.
- Aucun refresh token n'est émis (client public) ; l'utilisateur doit se reconnecter après l'expiration du token.
- Le contenu du token est celui par défaut : seul le `sub` (UUID) vient de l'utilisateur. L'e-mail, le nom et les rôles viendront à l'étape 4.

## 9. Arborescence

```
ecommerce-secure/  pom.xml  mvnw  mvnw.cmd  .mvn/  scripts/  README.md
├── user-service/     controller service repository entity dto mapper exception config  (+ tests)
├── order-service/    … + client/ (UserClient, PaymentClient)
└── payment-service/  …
```
