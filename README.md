# ecommerce-secure

Boutique en ligne d'électronique composée de trois microservices Spring Boot, sécurisés selon le principe **Zero Trust** : aucune confiance implicite au réseau, chaque appel est authentifié et autorisé.

## Architecture

| Module | Port | Rôle |
|---|---|---|
| `user-service` | 8081 | Comptes, rôles, vérification des mots de passe (BCrypt) |
| `order-service` | 8082 | Catalogue produits et commandes |
| `payment-service` | 8083 | Simulation de paiement (mode sandbox) |
| `authorization-server` | 9000 | Serveur OAuth 2.0 / OpenID Connect qui émet les jetons JWT |

Chaque service possède sa propre base H2 en mémoire.

## Éléments de sécurité

- **Authentification utilisateur** : flux *Authorization Code* avec PKCE (Spring Authorization Server). Le mot de passe est vérifié par `user-service`, seul détenteur des hachages.
- **Validation des jetons** : chaque service valide lui-même les JWT (signature, émetteur, expiration, rôles).
- **HTTPS et mTLS** : une autorité de certification interne délivre un certificat à chaque service. Seul `order-service` peut appeler `POST /payments`, avec en plus le JWT de l'utilisateur.
- **Autorisation** : rôles `CUSTOMER` et `ADMIN` (RBAC) et contrôle de la propriété des données (ABAC), avec refus par défaut.
- **Audit** : chaque décision d'accès est journalisée, sans jamais écrire de secret.

## Prérequis

- JDK 17 ou plus récent (Maven est fourni par le wrapper `mvnw`)

## Lancement

**1. Installer les certificats** (une seule fois)

```bash
./pki/install-certs.sh        # Linux / macOS / Git Bash
pki\install-certs.bat         # Windows
```

**2. Compiler et tester**

```bash
./mvnw clean verify
```

**3. Démarrer les services** (ordre : user, authorization, payment, order)

```bash
./scripts/start-all.sh        # Linux / macOS / Git Bash
scripts\start-all.bat         # Windows
```

Vérification : `https://localhost:8081/actuator/health` (le navigateur doit faire confiance à `pki/generated/ca.crt`).

**Swagger** (facultatif) : lancer avec le profil `demo`, par exemple `SPRING_PROFILES_ACTIVE=demo ./scripts/start-all.sh`, puis ouvrir `https://localhost:8081/swagger-ui.html` (ports 8081, 8082, 8083).

## Utilisation rapide

Compte de démonstration : `hamza.belhaj@example.com` / `Demo@2026` (administrateur : `admin@example.com`).


> Projet de démonstration : le mot de passe `Demo@2026` et les certificats versionnés ne doivent jamais être utilisés en production.