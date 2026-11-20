# Matrice d'accès — Application de commande et paiement e-commerce

Cette matrice est la **source de vérité** pour quatre usages : les annotations de sécurité du code, la configuration des URL publiques (configuration des services en Resource Server), les tests 401/403/404 (un test par ligne et par identité) et la section « Autorisation » du rapport.

## 1. Identités et légende

### 1.1 Identités prises en compte

Les CN sont ceux des certificats émis par l'autorité de certification du projet. Chaque CN est converti en une autorité Spring unique (table de correspondance dans la configuration, jamais déduite par manipulation de chaîne).

| Identité | Origine | Valeur d'identité | Autorité Spring |
|---|---|---|---|
| Anonyme | aucune preuve d'identité | — | — |
| `CUSTOMER` | JWT, claim `roles` | claim `sub` (UUID de l'utilisateur) | `ROLE_CUSTOMER` |
| `ADMIN` | JWT, claim `roles` | claim `sub` | `ROLE_ADMIN` |
| Service de commandes | certificat client mTLS, CN `order-service` | CN | `SERVICE_ORDER` |
| Serveur d'autorisation | certificat client mTLS, CN `authorization-server` | CN | `SERVICE_AUTH` |
| Service utilisateurs | certificat client mTLS, CN `user-service` | CN | `SERVICE_USER` (aucun droit sur les endpoints actuels) |
| Service de paiement | certificat client mTLS, CN `payment-service` | CN | `SERVICE_PAYMENT` (aucun droit sur les endpoints actuels) |

Un CN absent de cette table n'obtient aucune autorité de service.

### 1.2 Légende

| Symbole | Signification |
|---|---|
| ✅ | autorisé |
| ❌ | interdit |
| 🔒 | autorisé sous condition (règle ABAC référencée, voir §5) |
| — | non applicable |

### 1.3 Codes de refus

mTLS est configuré en mode **`want`** : le serveur demande un certificat client mais n'interrompt pas le handshake s'il est absent. La vérification du certificat est donc faite **au niveau applicatif**, et un certificat absent donne toujours une réponse HTTP (jamais un échec TLS).

| Situation | Code | Motif d'audit (`reason`) |
|---|---|---|
| Aucun jeton, jeton expiré, signature invalide, émetteur inconnu | **401** | `missing_token` / `invalid_token` |
| Endpoint réservé à un service, aucun certificat client présenté | **401** | `missing_certificate` |
| Jeton valide mais rôle insuffisant | **403** | `insufficient_role` |
| Certificat valide mais CN non autorisé pour cet endpoint | **403** | `service_not_allowed` |
| Utilisateur non propriétaire d'un profil (identifiant UUID) | **403** | `not_owner` |
| Utilisateur non propriétaire d'une commande ou d'un paiement (identifiant séquentiel) | **404** | `not_owner` |
| JWT relayé dont le `sub` ne correspond pas à la ressource ou au `ownerId` demandé (règles A5, A7) | **403** | `subject_mismatch` |

Toutes les réponses 401/403/404 produites par la sécurité utilisent le format `ApiError` du projet (`timestamp`, `status`, `error`, `message`, `path`, `details`). Le message envoyé au client est volontairement générique : le motif précis (`reason`) n'apparaît que dans le journal d'audit.

### 1.4 Combinaison certificat + JWT

Une requête porte deux preuves indépendantes : le certificat dit **quel service appelle**, le JWT dit **pour le compte de quel utilisateur**. Elles sont évaluées séparément, jamais fusionnées : un rôle du JWT n'accorde jamais une autorité de service, et inversement.

| Certificat | JWT | Interprétation | Exemple |
|---|---|---|---|
| absent | absent | Anonyme | inscription (U1), catalogue (O1) |
| absent | valide | Utilisateur direct (`CUSTOMER` ou `ADMIN`) | un client consulte ses commandes |
| valide (CN connu) | absent | Service seul, sans utilisateur | `authorization-server` appelle `/internal/auth/verify` (U6) |
| valide (CN connu) | valide | Service agissant **pour le compte** de l'utilisateur du JWT relayé | `order-service` appelle `POST /payments` (P1) ou `GET /users/{id}` (U3) |

Règles de décision :
1. Un endpoint marqué « service » exige le certificat **et**, si la ligne le précise, un JWT relayé cohérent (règles A5, A7).
2. Un endpoint marqué « service » appelé avec un JWT seul (sans certificat) est refusé : le rôle `ADMIN` ne remplace pas une autorité de service.
3. Pour les endpoints réservés aux utilisateurs, un certificat présenté est ignoré pour la décision, mais son CN apparaît dans l'audit comme service appelant.

---

## 2. user-service (port 8081)

| Réf. | Méthode et chemin | Anonyme | CUSTOMER | ADMIN | Service autorisé | Règle ABAC | Remarque |
|---|---|---|---|---|---|---|---|
| U1 | `POST /users` (inscription) | ✅ | ✅ | ✅ | — | — | Public par conception (création de compte). Le rôle `CUSTOMER` est toujours imposé côté serveur. |
| U2 | `GET /users` et `GET /users?email=` (liste et recherche) | ❌ | ❌ | ✅ | ❌ | — | Liste des comptes et recherche par email : administration uniquement (risque d'énumération de comptes). |
| U3 | `GET /users/{id}` | ❌ | 🔒 | ✅ | `SERVICE_ORDER` 🔒 | A1, A7 | Le service de commandes vérifie l'existence et l'état du client avant de créer une commande. Il doit relayer le JWT du client concerné. |
| U4 | `PUT /users/{id}` | ❌ | 🔒 | ✅ | ❌ | A1 | Profil modifiable par son propriétaire ou par un administrateur. L'email reste immuable. |
| U5 | `PATCH /users/{id}/status` | ❌ | ❌ | ✅ | ❌ | — | Activation / désactivation d'un compte : administration uniquement. |
| U6 | `POST /internal/auth/verify` | ❌ | ❌ | ❌ | `SERVICE_AUTH` | — | Aucun JWT (l'utilisateur n'est pas encore authentifié). Seul le serveur d'autorisation, identifié par son certificat, peut l'appeler. |
| U7 | `GET /actuator/health` | ✅ | ✅ | ✅ | ✅ | — | Sonde de santé uniquement, aucun détail exposé. |
| U8 | Swagger UI et `/v3/api-docs` | voir §6 | | | | | |

---

## 3. order-service (port 8082)

| Réf. | Méthode et chemin | Anonyme | CUSTOMER | ADMIN | Service autorisé | Règle ABAC | Remarque |
|---|---|---|---|---|---|---|---|
| O1 | `GET /products` | ✅ | ✅ | ✅ | — | — | Catalogue public. |
| O2 | `GET /products/{id}` | ✅ | ✅ | ✅ | — | — | Catalogue public. |
| O3 | `POST /products` | ❌ | ❌ | ✅ | ❌ | — | Gestion du catalogue : administration. |
| O4 | `PUT /products/{id}` | ❌ | ❌ | ✅ | ❌ | — | Gestion du catalogue : administration. |
| O5 | `POST /orders` | ❌ | 🔒 | ❌ | ❌ | A6 | `ownerId` n'est plus dans le corps : il est déduit du `sub`. Un `ownerId` envoyé dans le corps donne **400** (propriété inconnue). |
| O6 | `GET /orders/{id}` | ❌ | 🔒 | ✅ | ❌ | A2 | Propriétaire ou administrateur. Non-propriétaire : **404**. |
| O7 | `GET /orders` | ❌ | 🔒 | ✅ | ❌ | A4 | Un client ne voit que ses commandes. Un administrateur voit tout, avec filtre `ownerId` optionnel. |
| O8 | `GET /actuator/health` | ✅ | ✅ | ✅ | ✅ | — | Sonde de santé uniquement. |
| O9 | Swagger UI et `/v3/api-docs` | voir §6 | | | | | |

---

## 4. payment-service (port 8083)

| Réf. | Méthode et chemin | Anonyme | CUSTOMER | ADMIN | Service autorisé | Règle ABAC | Remarque |
|---|---|---|---|---|---|---|---|
| P1 | `POST /payments` | ❌ | ❌ | ❌ | `SERVICE_ORDER` 🔒 | A5 | Appel interne uniquement. Le JWT de l'utilisateur est relayé : `body.ownerId` doit être égal au `sub` du jeton relayé. |
| P2 | `GET /payments/{id}` | ❌ | 🔒 | ✅ | ❌ | A3 | Propriétaire ou administrateur. Non-propriétaire : **404**. |
| P3 | `GET /payments/order/{orderId}` | ❌ | 🔒 | ✅ | ❌ | A3 | Propriétaire ou administrateur. Non-propriétaire : **404**, identique au cas « aucun paiement pour cette commande ». |
| P4 | `GET /payments` | ❌ | 🔒 | ✅ | ❌ | A4 | Un client ne voit que ses paiements. Un administrateur voit tout, avec filtre `ownerId` optionnel. |
| P5 | `POST /payments/{id}/collect` | ❌ | ❌ | ✅ | ❌ | — | Encaissement d'un paiement à la livraison : opération d'administration (simule l'agent de livraison). |
| P6 | `GET /actuator/health` | ✅ | ✅ | ✅ | ✅ | — | Sonde de santé uniquement. |
| P7 | Swagger UI et `/v3/api-docs` | voir §6 | | | | | |

---

## 5. Règles ABAC (contrôle par attributs)

| Réf. | Règle | Attributs utilisés | Appliquée sur |
|---|---|---|---|
| A1 | Un `CUSTOMER` n'accède à un profil que si `{id} == sub`. | ressource : `id` ; sujet : `sub`, rôle | U3, U4 |
| A2 | Un `CUSTOMER` n'accède à une commande que si `order.ownerId == sub`. | ressource : `ownerId` ; sujet : `sub`, rôle | O6 |
| A3 | Un `CUSTOMER` n'accède à un paiement que si `payment.ownerId == sub`. | ressource : `ownerId` ; sujet : `sub`, rôle | P2, P3 |
| A4 | Les listes sont filtrées selon l'identité : un `CUSTOMER` obtient uniquement ses éléments. S'il fournit un `ownerId` différent de son `sub`, la réponse est 403. Un `ADMIN` peut tout lister. | sujet : `sub`, rôle ; paramètre `ownerId` | O7, P4 |
| A5 | Le propriétaire du paiement à créer (`body.ownerId`) doit correspondre au `sub` du jeton relayé par `order-service`. | requête : `ownerId` ; sujet : `sub` du jeton relayé ; appelant : CN | P1 |
| A6 | `ownerId` d'une commande n'est jamais fourni par le client : il est égal au `sub` du jeton. | sujet : `sub` | O5 |
| A7 | Quand l'appelant est `SERVICE_ORDER`, le `sub` du JWT relayé doit être égal à `{id}` : le service ne peut lire que l'utilisateur pour le compte duquel il agit. Sans JWT relayé : 401 ; `sub` différent : 403 `subject_mismatch`. | appelant : CN ; sujet : `sub` du jeton relayé ; ressource : `id` | U3 |

---

## 6. URL publiques (liste définitive)

Seules ces URL sont accessibles sans authentification. Tout le reste exige au minimum une authentification, puis la règle de la matrice. Cette liste est à reprendre telle quelle dans la configuration de la chaîne de filtres (configuration de la chaîne de filtres).

| Service | Méthode et chemin | Condition |
|---|---|---|
| user-service | `POST /users` | toujours |
| user-service | `GET /actuator/health` | toujours |
| order-service | `GET /products` | toujours |
| order-service | `GET /products/{id}` | toujours |
| order-service | `GET /actuator/health` | toujours |
| payment-service | `GET /actuator/health` | toujours |
| les trois services | `/swagger-ui.html`, `/swagger-ui/**`, `/v3/api-docs/**` | uniquement avec le profil Spring `demo` ; fermés sinon (D4) |

Cas particulier : `/internal/**` n'est pas public. Il est réservé à l'autorité `SERVICE_AUTH` dès la chaîne de filtres.

Les endpoints OIDC du serveur d'autorisation (découverte, JWKS, autorisation, jeton) sont hors du périmètre de cette matrice : ils appartiennent au module du serveur d'autorisation.

---

## 7. Décisions

Choix retenus dans cette version, à justifier dans le rapport.

| # | Question | Choix retenu | Justification |
|---|---|---|---|
| D1 | Un `ADMIN` peut-il passer une commande ? | Non | Séparation des rôles : un administrateur gère la boutique, il n'achète pas au nom d'un client. |
| D2 | Réponse pour un non-propriétaire | **404** pour commandes et paiements, **403** pour les profils. `not_owner` est conservé dans l'audit. | Les identifiants de commandes et de paiements sont séquentiels (`Long`) et énumérables : un 403 confirmerait qu'une ressource existe. Les profils utilisent des UUID non devinables, donc le 403 explicite est sans risque. Le motif réel reste visible dans le journal d'audit. |
| D3 | Qui peut encaisser (`collect`) ? | `ADMIN` | Pas de rôle « livreur » dans le périmètre du projet. |
| D4 | Swagger UI et `/v3/api-docs` | Désactivés par défaut, publics uniquement avec le profil `demo` | Limiter la surface d'exposition hors démonstration. |
| D5 | `GET /products` public ou authentifié ? | Public | Catalogue consultable sans compte, comme une vitrine de boutique. |
| D6 | `payment-service` appelle-t-il d'autres services ? | Non | Aucun appel de ce type dans le code actuel. À compléter si le flux change. |
| D7 | Refus par défaut : `denyAll()` ou `authenticated()` ? | `anyRequest().authenticated()` en chaîne de filtres, **plus** `@PreAuthorize` obligatoire sur chaque méthode de contrôleur, vérifié par un test de réflexion | `denyAll()` obligerait à recopier toute la matrice dans la chaîne de filtres, en plus des annotations : deux endroits à maintenir, donc un risque de divergence. Avec `authenticated()`, la chaîne ne gère que les URL publiques et `/internal/**`, et la matrice n'est écrite qu'une fois, dans les annotations. Le risque de l'oubli d'annotation (un endpoint accessible à tout utilisateur connecté) est supprimé par le test de réflexion, qui échoue si une méthode n'est ni annotée ni dans la liste publique du §6. |

---

## 8. Principes appliqués (Zero Trust)

1. **Refus par défaut** : une URL absente du §6 exige une authentification, et chaque méthode de contrôleur doit porter une règle explicite (D7). Une méthode sans règle fait échouer les tests.
2. **Chaque service décide pour lui-même** : `payment-service` ne fait pas confiance à `order-service` sur la seule base du réseau. Il vérifie le certificat (qui appelle) et le jeton relayé (pour le compte de qui).
3. **Moindre privilège** : chaque service n'a que les droits nécessaires. Seul `SERVICE_AUTH` atteint `/internal/**`, seul `SERVICE_ORDER` crée des paiements ou lit un utilisateur, et seulement pour l'utilisateur du jeton relayé.
4. **Aucune identité fournie par le client** : `ownerId` est déduit du jeton, jamais lu dans le corps de la commande.
5. **Pas de fuite d'information** : les messages d'erreur sont génériques, et les identifiants énumérables ne révèlent pas l'existence d'une ressource (D2).
6. **Traçabilité** : chaque décision (accordée ou refusée) est journalisée avec l'identité (`sub` ou CN), la méthode, le chemin, la décision et le motif, sans jamais écrire un jeton dans les logs, sans chaîne de requête, et avec les emails masqués.

---

## 9. Utilisation dans les tests

Chaque ligne des tableaux §2 à §4 donne lieu à un test paramétré, pour chacune des identités de test suivantes.

| Identité de test | Si ✅ | Si ❌ | Si 🔒 (propriétaire / cohérent) | Si 🔒 (autre / incohérent) |
|---|---|---|---|---|
| Anonyme | 2xx (URL publique) | 401 | 401 | 401 |
| Client A (propriétaire) | 2xx | 403 | 2xx | — |
| Client B (autre client) | 2xx | 403 | — | 403 (profil) ou 404 (commande, paiement) |
| Administrateur | 2xx | 403 | 2xx | 2xx |
| **Service autorisé + JWT relayé cohérent** (CN autorisé, `sub` du JWT = propriétaire ou `{id}`) | 2xx | 403 | 2xx | — |
| **Service autorisé + JWT relayé d'un autre utilisateur** (CN autorisé, `sub` ≠ propriétaire ou `{id}`) | — | 403 | — | 403 `subject_mismatch` |
| Service autorisé sans JWT | 401 sur les endpoints qui exigent un JWT relayé ; 2xx pour U6 | 403 | 401 | 401 |
| Service dont le CN n'est pas autorisé (+ JWT) | 2xx si l'endpoint n'est pas réservé aux services | 403 `service_not_allowed` | 403 | 403 |
| Appel sans certificat sur un endpoint réservé aux services | — | 401 `missing_certificate` | 401 | 401 |

Les tests d'intégration avec de vrais certificats sont distincts de ces tests unitaires : les premiers (démonstrations avec les keystores et truststores générés) prouvent le mTLS, les seconds simulent les deux identités avec les post-processeurs `jwt()` et `x509()` de `spring-security-test`.
