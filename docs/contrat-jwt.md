# Contrat des claims JWT (étape 4)

Document destiné à la personne qui écrit les règles d'autorisation (étape 5 : validation des JWT, RBAC, ABAC).
Il décrit **exactement** ce que le serveur d'autorisation (`http://localhost:9000`) met dans les tokens. Toute modification
de ce format doit être signalée à l'autre développeur.

## 1. Tableau des claims

| Claim | Valeur | Access token | ID token |
|---|---|---|---|
| `sub` | UUID de l'utilisateur (identique à l'`id` dans `user-service`) | oui | oui |
| `email` | adresse de l'utilisateur, **uniquement si le scope `email` est accordé** | oui | oui |
| `name` | prénom et nom séparés par une espace (ex. `Hamza Belhaj`), **uniquement si le scope `profile` est accordé** | oui | oui |
| `roles` | tableau de textes **sans préfixe** (ex. `["CUSTOMER"]`), **toujours présent**, trié, sans doublon | **oui** | **non** |
| `scope`, `iss`, `aud`, `exp`, `iat`, `nbf`, `jti` | standards, inchangés | oui | selon la norme |

Règles :

- `roles` ne contient **pas** le préfixe `ROLE_`. Valeurs possibles aujourd'hui : `CUSTOMER`, `ADMIN`. Le tableau peut être vide, mais le claim est toujours présent dans l'access token.
- Le token ne contient **jamais** de mot de passe, de hash, de téléphone, d'adresse, de ville ni d'état du compte.
- `email_verified` est **volontairement absent** : le projet ne vérifie pas les emails.
- L'access token est un JWT autoportant signé en RS256, valable **15 minutes**. Les clés publiques sont publiées sur `http://localhost:9000/oauth2/jwks`.
- `roles` est destiné aux API (access token). L'ID token sert à l'application cliente pour connaître l'identité : il n'a pas de `roles` et ne doit **pas** être envoyé aux API.

## 2. Exemples de payloads décodés

Valeurs de démonstration : `exp`, `iat`, `nbf` et `jti` changent à chaque token. L'exemple « CUSTOMER » est un access token réellement émis par le serveur avec le scope `openid profile email` ; celui de l'administrateur suit le même format avec les données du compte `admin@example.com`.

**Utilisateur `CUSTOMER` (Hamza Belhaj)**

```json
{
  "sub": "00000000-0000-4000-8000-000000000001",
  "aud": "ecommerce-web",
  "nbf": 1795041376,
  "scope": ["openid", "profile", "email"],
  "roles": ["CUSTOMER"],
  "iss": "http://localhost:9000",
  "name": "Hamza Belhaj",
  "exp": 1795042276,
  "iat": 1795041376,
  "jti": "462380df-c22f-4938-bd56-92fd61db191e",
  "email": "hamza.belhaj@example.com"
}
```

**Administrateur (`ADMIN`)**

```json
{
  "sub": "00000000-0000-4000-8000-000000000008",
  "aud": "ecommerce-web",
  "nbf": 1795041500,
  "scope": ["openid", "profile", "email"],
  "roles": ["ADMIN"],
  "iss": "http://localhost:9000",
  "name": "Administrateur Boutique",
  "exp": 1795042400,
  "iat": 1795041500,
  "jti": "0b5f6d1e-7c3a-4e55-9a41-3d2c8f7a6b10",
  "email": "admin@example.com"
}
```

**ID token de Hamza** (pour comparaison : pas de `roles`) :

```json
{
  "sub": "00000000-0000-4000-8000-000000000001",
  "aud": "ecommerce-web",
  "azp": "ecommerce-web",
  "auth_time": 1795041006,
  "iss": "http://localhost:9000",
  "name": "Hamza Belhaj",
  "exp": 1795043177,
  "iat": 1795041377,
  "jti": "dcbac248-f570-437c-bab2-7fc72201b0fc",
  "email": "hamza.belhaj@example.com",
  "sid": "hEXP87HOYNDpjCIAhnofMzC5o3sWXhiFDgwArziDSUg"
}
```

## 3. Précisions pour l'autre développeur

- **Rôles** : `roles` est sans préfixe. C'est à votre code d'ajouter `ROLE_` pour obtenir des autorités Spring (`ROLE_CUSTOMER`, `ROLE_ADMIN`) utilisables avec `hasRole("ADMIN")`. Avec Spring Security, cela se règle sur le convertisseur de JWT :
  ```java
  JwtGrantedAuthoritiesConverter converter = new JwtGrantedAuthoritiesConverter();
  converter.setAuthoritiesClaimName("roles");
  converter.setAuthorityPrefix("ROLE_");
  ```
  (Le convertisseur par défaut lit `scope`, pas `roles` : sans ce réglage, aucun rôle ne sera reconnu.)
- **Propriétaire d'une ressource (ABAC)** : `sub` est l'UUID de l'utilisateur ; c'est la valeur à comparer avec `ownerId`. Ne jamais faire confiance à un `ownerId` venant du corps de la requête.
- **Aucun autre champ n'est garanti.** Ne comptez que sur `sub`, `roles`, `iss`, `exp`, `iat`. `email` et `name` ne sont là que si le client a demandé les scopes `email` et `profile`.
- **Émetteur** : `iss` vaut exactement `http://localhost:9000` (pas `127.0.0.1`).
- **Audience** : `aud` vaut `ecommerce-web` (l'identifiant du client), **pas** le nom d'un des trois services. Il ne faut donc pas exiger une audience propre à chaque service à l'étape 5, sauf décision commune de changer le serveur.
- `scope` est un tableau dans le payload décodé.

## 4. Limites

- **Rôles figés dans le token** : les rôles sont copiés dans le token à la connexion et ne sont pas relus ensuite. Un changement de rôle dans `user-service` n'a d'effet qu'à la **prochaine connexion** ; jusque-là, l'ancien token garde l'ancien rôle, au plus 15 minutes.
- **Signé mais non chiffré** : tout détenteur du token peut lire son contenu (il suffit de le décoder en base 64). Aucun secret ne doit donc y figurer, et un token ne doit jamais être écrit dans les logs.
- **`roles` est un claim propre au projet** : OpenID Connect ne définit pas de claim de rôles. Aucun outil externe ne le reconnaît automatiquement.
- **`email` non vérifié** : le projet ne vérifie pas les adresses email ; ne jamais s'en servir comme preuve d'identité. L'identifiant fiable est `sub`.
- **Durée de vie de l'ID token** : l'access token vit 15 minutes, mais l'ID token garde la durée par défaut de Spring Authorization Server (30 minutes). Il n'est pas destiné aux API.
- **Clés en mémoire** : les clés de signature changent à chaque redémarrage du serveur d'autorisation, ce qui invalide tous les tokens déjà émis.
