# Validation des JWT par les services (étape 5)

Depuis l'étape 5, **chacun des trois services (`user-service`, `payment-service`, `order-service`) valide lui-même les tokens d'accès** émis par le serveur d'autorisation (`authorization-server`). Aucun service ne fait confiance à un autre pour authentifier un appelant : tout est protégé, sauf une courte liste d'URL publiques (section 4).

Ce document décrit uniquement l'**authentification**. Les **règles d'autorisation** (qui a le droit de faire quoi : rôles, propriétaire d'une commande, etc.) sont le travail de la personne B et ne sont **pas encore appliquées** : un token valide donne accès à toutes les URL protégées.

## 1. Comment un service valide un token

À chaque requête vers une URL protégée, dans cet ordre :

1. **Extraction** : le service lit l'en-tête `Authorization: Bearer <token>`. Sans cet en-tête, la requête est anonyme et reçoit un **401** (« Authentication required »).
2. **Lecture du JWT** : un token mal formé est refusé.
3. **Signature** : le service vérifie la signature RS256 avec les clés publiques du serveur d'autorisation (`http://localhost:9000/oauth2/jwks`). Ces clés sont récupérées **à la première requête authentifiée**, puis conservées en mémoire et rechargées si un identifiant de clé (`kid`) inconnu apparaît.
4. **Horodatage** : le token ne doit être ni expiré (`exp`) ni utilisé trop tôt (`nbf`), avec la tolérance d'horloge par défaut de Spring Security (60 secondes).
5. **Émetteur** : le claim `iss` doit être **exactement** égal à `issuer-uri` (par défaut `http://localhost:9000`).
6. **Claim `roles`** : il doit être présent. Cela écarte notamment l'ID token, qui n'en contient pas, et qui ne doit jamais servir d'access token.
7. **Résultat** : si tout est valide, la requête est authentifiée et poursuit son chemin. Sinon, la réponse est un **401** au format d'erreur du projet, avec le **même message générique** « Invalid or expired token » quelle que soit la cause, pour ne rien révéler à un attaquant.

Le service ne contacte **jamais** le serveur d'autorisation pour chaque requête : une fois les clés connues, la validation est locale.

## 2. Configuration

| Propriété | Variable d'environnement | Défaut | Rôle |
|---|---|---|---|
| `spring.security.oauth2.resourceserver.jwt.issuer-uri` | `AUTH_ISSUER_URI` | `http://localhost:9000` | Émetteur attendu et adresse où trouver les clés publiques. |
| `springdoc.api-docs.enabled` | | `false` | Définition OpenAPI désactivée par défaut. |
| `springdoc.swagger-ui.enabled` | | `false` | Swagger UI désactivé par défaut. |

Le **décodeur est défini dans le code** (`JwtDecoderConfig`) et non par simple propriété, pour appliquer nos propres contrôles (section 3). Il est **paresseux** : un service démarre même si le serveur d'autorisation est arrêté.

## 3. Classes ajoutées (package `security` de chaque service)

| Classe | Rôle |
|---|---|
| `SecurityConfig` | Chaîne de filtres : sans session, URL publiques, tout le reste authentifié, validation JWT. |
| `JwtDecoderConfig` | Décodeur paresseux (clés récupérées à la première requête authentifiée). |
| `TokenValidators`, `RolesClaimValidator` | Contrôles du token : horodatage, émetteur, présence de `roles`. Réutilisés par les tests. |
| `ApiAuthenticationEntryPoint`, `ApiAccessDeniedHandler`, `ApiErrorWriter` | Réponses 401 et 403 au format `ApiError` du projet. |
| `AuthorizationServerUnavailableFilter` | Réponse 503 quand le serveur d'autorisation est injoignable. |
| `SwaggerSecurityConfig` | Rend Swagger public, **uniquement** avec le profil `demo`. |
| `BearerTokenRelayInterceptor` (`order-service` seulement) | Relais du token vers les autres services (section 7). |

La protection contre le CSRF est désactivée **volontairement** : l'API n'utilise ni session ni cookie d'authentification, le token voyage dans l'en-tête `Authorization`, qu'un navigateur n'ajoute jamais de lui-même.

## 4. URL publiques par service

| Service | Méthode et URL | Remarque |
|---|---|---|
| `user-service` | `POST /users` | Inscription. |
| `user-service` | `GET /actuator/health` | Sonde de santé. |
| `user-service` | `POST /internal/auth/verify` | **Provisoire**, voir section 5. |
| `order-service` | `GET /products`, `GET /products/{id}` | Catalogue en lecture seule. |
| `order-service` | `GET /actuator/health` | Sonde de santé. |
| `payment-service` | `GET /actuator/health` | Sonde de santé. |

Chaque URL publique est déclarée **avec sa méthode** : `POST /users` est public, `GET /users` ne l'est pas. Tout le reste (y compris un chemin inconnu) exige un token.

Avec le profil `demo`, s'ajoutent `/swagger-ui.html`, `/swagger-ui/**` et `/v3/api-docs/**` (section 6).

## 5. Exception provisoire : `POST /internal/auth/verify`

Le serveur d'autorisation appelle cet endpoint de `user-service` pour vérifier le mot de passe d'une personne qui se connecte. Il ne peut pas présenter de JWT, puisque c'est lui qui les fabrique : l'endpoint est donc **ouvert, uniquement pour cette méthode et ce chemin** (`GET /internal/auth/verify` ou `POST /internal/auth/autre` exigent un token). Il sera réservé au seul serveur d'autorisation par un certificat de service aux **étapes 6 et 7** (mTLS). D'ici là, n'importe quel client du réseau peut l'appeler.

## 6. Swagger et le profil `demo`

Par défaut, Swagger UI et la définition OpenAPI sont **désactivés** : sans token, `/swagger-ui.html` et `/v3/api-docs` répondent 401 ; avec un token valide, 404.

Le profil `demo` (fichier `application-demo.yml` de chaque service) les **active et les rend publics**. La définition OpenAPI déclare un schéma d'authentification Bearer JWT : le bouton **Authorize** de Swagger UI permet de coller un access token pour tester les URL protégées. Le profil `demo` n'ouvre rien d'autre.

À n'utiliser que pour une démonstration : la documentation de l'API révèle tous les endpoints d'un service.

Pour activer le profil :

| Façon de lancer | Commande |
|---|---|
| `scripts\start-all.bat` (Windows, `cmd`) | `set SPRING_PROFILES_ACTIVE=demo` puis `scripts\start-all.bat` |
| `scripts/start-all.sh` (Git Bash, Linux, macOS) | `SPRING_PROFILES_ACTIVE=demo ./scripts/start-all.sh` |
| Un service avec Maven | `mvnw.cmd -pl user-service spring-boot:run "-Dspring-boot.run.profiles=demo"` |
| IntelliJ (édition Community) | *Edit Configurations*, *Modify options*, *Program arguments* : `--spring.profiles.active=demo` |

Les adresses Swagger affichées à la fin de `start-all.*` ne fonctionnent donc qu'avec ce profil. Les quatre services reçoivent la variable d'environnement ; elle est sans effet sur `authorization-server`, qui n'a pas de profil `demo`.

## 7. Relais du token par `order-service`

Créer une commande oblige `order-service` à appeler `user-service` (vérifier le compte) puis `payment-service` (débiter). Chacun valide le token lui-même : `order-service` **transmet donc, inchangé, le token de la personne qui l'appelle** (`BearerTokenRelayInterceptor`, branché sur les deux clients HTTP dans `RestClientConfig`, y compris pour la nouvelle tentative de paiement).

- **Échec fermé** : s'il n'y a pas de JWT dans le contexte de sécurité, l'appel n'est **pas envoyé** (jamais d'appel anonyme).
- Le token n'est jamais journalisé ni écrit dans un message d'erreur.
- Les appels sont faits dans le même thread que la requête reçue : il n'y a pas de traitement asynchrone.

## 8. Réponses d'erreur de sécurité

Toutes au format `ApiError` du projet (`timestamp`, `status`, `error`, `message`, `path`, `details`).

| Statut | Quand | `message` |
|---|---|---|
| 401 | Pas d'en-tête `Authorization: Bearer` | `Authentication required` |
| 401 | Token mal formé, falsifié, expiré, d'un autre émetteur, signé par une autre clé, ou sans `roles` | `Invalid or expired token` (en-tête `WWW-Authenticate: Bearer`) |
| 403 | Réservé aux règles d'autorisation de B (gestionnaire prêt, aucune règle encore appliquée) | `Access denied` |
| 503 | Le serveur d'autorisation est injoignable et le token ne peut pas être contrôlé | `Authentication service unavailable` |

## 9. Limites connues

- Le **serveur d'autorisation doit être joignable à la première requête authentifiée** de chaque service (récupération des clés). Un service démarre sans lui, mais répond 503 aux requêtes avec token tant qu'il est injoignable. Il se rétablit seul quand le serveur revient, sans redémarrage.
- Les **clés de signature sont générées en mémoire** : un redémarrage du serveur d'autorisation les change et **invalide tous les tokens déjà émis** (il faut en redemander un).
- **Aucun contrôle d'audience** (`aud`) : un token valide est accepté par n'importe lequel des trois services.
- **`/internal/**` est ouvert** (en pratique `POST /internal/auth/verify`) jusqu'au mTLS des étapes 6 et 7.
- **Aucune règle de rôle** : un token valide, quel que soit son rôle, accède à toutes les URL protégées. C'est l'étape suivante, côté B.
- Un token reste valable jusqu'à son expiration (15 minutes) même si le compte est ensuite désactivé ou change de rôle : il n'y a pas de révocation.

## 10. Dépannage

| Symptôme | Cause probable | Que faire |
|---|---|---|
| 401 « Invalid or expired token », token qui semblait bon | Émetteur différent : le token a été obtenu via `127.0.0.1` et non `localhost` (le claim `iss` ne correspond plus à `issuer-uri`) | Obtenir le token en passant par `http://localhost:9000`. |
| 401 « Invalid or expired token » | Token expiré (15 minutes) | Redemander un token. |
| 401 « Invalid or expired token » après un redémarrage de `authorization-server` | Les clés ont changé, l'ancien token n'est plus valide | Redemander un token. |
| 401 « Invalid or expired token » | L'ID token a été envoyé à la place de l'access token (pas de claim `roles`) | Utiliser l'access token. |
| 401 « Authentication required » | En-tête `Authorization` absent, ou schéma autre que `Bearer` | Envoyer `Authorization: Bearer <access_token>`. |
| 503 « Authentication service unavailable » | `authorization-server` arrêté ou injoignable | Le relancer ; le service se rétablit seul. |
| Swagger : 401 sur `/swagger-ui.html` | Profil `demo` non activé | Voir section 6. |
| Postman : `getaddrinfo ENOTFOUND get` | La méthode a été saisie dans la barre d'URL (`GET /users`) | Saisir `http://localhost:8081/users` et choisir GET dans le menu. |

Pour obtenir un token pas à pas, voir [obtenir-un-token.md](obtenir-un-token.md) ; pour le contenu des tokens, [contrat-jwt.md](contrat-jwt.md).

## 11. Vérification rapide

Avec un access token dans `TOKEN` (valable 15 minutes) :

```bash
curl -i localhost:8082/orders                                         # 401
curl -i -H "Authorization: Bearer $TOKEN" localhost:8082/orders       # 200
curl -i localhost:8082/products                                       # 200 (catalogue public)
curl -i localhost:8081/actuator/health                                # 200
```
