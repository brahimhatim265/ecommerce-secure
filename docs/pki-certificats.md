# PKI de démonstration : autorité de certification interne et certificats des services (étape 6)

L'étape 6 prépare **toute l'infrastructure de clés et de certificats** dont les services auront besoin pour communiquer en **HTTPS avec authentification mutuelle (mTLS)** à l'étape 7. Elle est entièrement générée avec l'outil `keytool` du JDK : aucun OpenSSL, aucun Docker, aucune dépendance Maven.

> **Cette étape n'active rien dans les services.** Les quatre modules restent en HTTP tant que l'étape 7 n'a pas branché TLS. Les scripts et les artefacts de `pki/` n'ont aucun effet sur le build Maven.

**En trois commandes** (depuis la racine du projet) :

| | Windows | Linux / macOS / Git Bash |
|---|---|---|
| Générer | `pki\generate-pki.bat` | `./pki/generate-pki.sh` |
| Vérifier | `pki\verify-pki.bat` | `./pki/verify-pki.sh` |
| Régénérer | `pki\generate-pki.bat /force` | `./pki/generate-pki.sh --force` |

Les artefacts de `pki/generated/` sont **déjà versionnés** : il n'est pas nécessaire de régénérer pour lancer le projet.

## 1. Vue d'ensemble

```
+---------------------------------------------------+
|  CA interne : « ecommerce-secure Internal CA »     |
|  RSA 4096 · 10 ans · CA:TRUE, pathlen:0            |
|  clé privée : pki/ca-private/ca.p12                |
|  (HORS DÉPÔT : ne quitte jamais la machine)        |
+-----------------------+---------------------------+
                        |  signe
      +-----------------+-----------------+-------------------+
      |                 |                 |                   |
      v                 v                 v                   v
+------------+   +-------------+   +---------------+   +------------------+
| user-      |   | order-      |   | payment-      |   | authorization-   |
| service    |   | service     |   | service       |   | server           |
+------------+   +-------------+   +---------------+   +------------------+
   CN = nom du service · SAN : localhost, nom du service, 127.0.0.1
   EKU : serverAuth + clientAuth · RSA 2048 · 365 jours
   chaque service a son keystore : clé privée + chaîne [service, CA]

+---------------------------------------------------+
|  truststore.p12 (COMMUN) : le certificat PUBLIC    |
|  de la CA, et rien d'autre                         |
+---------------------------------------------------+

Certificats « négatifs » (tests d'échec, jamais utilisés en fonctionnement normal) :
  rogue-service            signé par la CA du projet, valide, mais CN hors liste
  expired-order-service    CN order-service, signé par la CA du projet, déjà expiré
  untrusted-order-service  CN order-service, signé par UNE AUTRE CA (jamais dans le truststore)
```

Deux idées à retenir :

* **Le service qui présente un certificat dit *qui il est*** (son CN). L'autre partie vérifie que ce certificat a bien été signé par la CA qu'elle connaît (son truststore).
* Chaque service est **à la fois serveur** (il reçoit des appels) **et client** (il en émet) : son certificat porte donc les deux usages `serverAuth` et `clientAuth`.

## 2. Glossaire simple

| Terme | Définition |
|---|---|
| **CA** (autorité de certification) | Entité de confiance qui **signe** les certificats. Celui qui détient sa clé privée peut fabriquer n'importe quelle identité de service : cette clé reste hors du dépôt. |
| **Certificat** | Carte d'identité numérique : un nom, une **clé publique**, une période de validité et la **signature** de la CA qui l'a émis. Il est public. |
| **CN** (*Common Name*) | Le nom du sujet du certificat. Ici : le nom du service (`order-service`). C'est lui que le serveur lira, à l'étape 7, pour savoir **quel service l'appelle**. |
| **SAN** (*Subject Alternative Name*) | Liste des noms de machine pour lesquels le certificat est valable (`localhost`, `127.0.0.1`…). C'est le SAN, **pas le CN**, que le client compare au nom de l'hôte qu'il contacte (vérification du nom d'hôte). |
| **EKU** (*Extended Key Usage*) | Usages autorisés du certificat : `serverAuth` (s'identifier comme serveur TLS) et `clientAuth` (s'identifier comme client TLS). |
| **Keystore** | Fichier (ici PKCS12, `.p12`) qui contient **la clé privée d'un service et sa chaîne de certification**. Il prouve *qui je suis*. Il est secret. |
| **Truststore** | Fichier qui contient les **certificats auxquels on fait confiance** (ici : la CA seule). Il décide *à qui je fais confiance*. Il est public par nature. |
| **Chaîne de certification** | Suite de certificats reliant un certificat à une CA de confiance. Ici elle compte **2 certificats** : celui du service, puis celui de la CA. |

## 3. Contrat d'identité

Le CN est le nom du service, **tout en minuscules, avec des tirets, sans espace**. Sujet complet d'un service : `CN=<service>, OU=ecommerce-secure, O=ENSIAS, C=MA`. Sujet de la CA : `CN=ecommerce-secure Internal CA, OU=ecommerce-secure, O=ENSIAS, C=MA`.

| Service | Port | CN | Alias dans le keystore | Dossier |
|---|---|---|---|---|
| `user-service` | 8081 | `user-service` | `user-service` | `pki/generated/user-service/` |
| `order-service` | 8082 | `order-service` | `order-service` | `pki/generated/order-service/` |
| `payment-service` | 8083 | `payment-service` | `payment-service` | `pki/generated/payment-service/` |
| `authorization-server` | 9000 | `authorization-server` | `authorization-server` | `pki/generated/authorization-server/` |

Paramètres techniques :

| Élément | CA | Certificat de service |
|---|---|---|
| Algorithme et taille | RSA 4096 | RSA 2048 |
| Signature | `SHA256withRSA` | `SHA256withRSA` |
| Validité | 3650 jours | 365 jours |
| Début de validité | 1 jour avant la génération | 1 jour avant la génération (tolérance d'horloge) |
| `BasicConstraints` | critique, `CA:TRUE`, `pathlen:0` | `CA:FALSE` |
| `KeyUsage` | critique, `keyCertSign`, `cRLSign` | critique, `digitalSignature`, `keyEncipherment` |
| `ExtendedKeyUsage` | aucun | `serverAuth`, `clientAuth` |
| SAN | aucun | `dns:localhost`, `dns:<service>`, `ip:127.0.0.1` |
| Format des magasins | PKCS12 uniquement (pas de JKS) | |

## 4. Fichiers générés

| Fichier | Contenu | Secret ? | Rôle |
|---|---|---|---|
| `pki/ca-private/ca.p12` | clé privée de la CA | **OUI, hors dépôt** (`.gitignore`) | Signer les certificats. Ne doit jamais être copié ailleurs. |
| `pki/ca-private/other-ca.p12` | clé d'une *autre* CA | **OUI, hors dépôt** | Uniquement pour fabriquer `untrusted-order-service`. |
| `pki/generated/ca.crt` | certificat **public** de la CA (PEM) | non | `curl --cacert`, documentation, vérification. |
| `pki/generated/truststore.p12` | une seule entrée : le certificat public de la CA (alias `ca`) | non | Truststore commun à tous les services. |
| `pki/generated/<service>/<service>.p12` | keystore : clé privée du service + chaîne [service, CA] + entrée `ca` | oui (démo versionnée) | Identité du service en TLS (serveur **et** client). |
| `pki/generated/<service>/<service>.crt` | certificat **public** du service (PEM) | non | Inspection, `keytool -printcert`. |
| `pki/generated/negative-tests/rogue-service/rogue-service.p12` | certificat valide, CN hors liste | démo | Test d'échec applicatif (voir § 8). |
| `pki/generated/negative-tests/expired-order-service/expired-order-service.p12` | certificat expiré | démo | Test d'échec TLS (voir § 8). |
| `pki/generated/negative-tests/untrusted-order-service/untrusted-order-service.p12` | signé par une autre CA | démo | Test d'échec TLS (voir § 8). |
| `pki/generate-pki.sh`, `pki/generate-pki.bat` | scripts de génération | non | Mêmes artefacts, mêmes noms. |
| `pki/verify-pki.sh`, `pki/verify-pki.bat`, `pki/VerifyPki.java` | programme de vérification | non | Contrôle objectif (PASS / FAIL). |
| `pki/.gitattributes` | fins de ligne imposées | non | `.sh` en LF, `.bat` en CRLF (un `.sh` en CRLF ne s'exécute plus). |

Un keystore de service contient **deux entrées** : la **clé privée du service** (alias = nom du service, chaîne de 2 certificats) et le **certificat public de la CA** (alias `ca`, nécessaire pour construire la chaîne). Il ne contient **jamais** la clé privée de la CA.

## 5. Générer, vérifier, régénérer

### Variables d'environnement

| Variable | Rôle | Valeur par défaut |
|---|---|---|
| `PKI_PASSWORD` | mot de passe des keystores et du truststore | `ecommerce-demo-pki` (**démonstration**, un avertissement s'affiche) |
| `PKI_CA_PASSWORD` | mot de passe de la clé privée de la CA | `ecommerce-demo-pki` (**démonstration**, un avertissement s'affiche) |

Les mots de passe sont transmis à `keytool` par variable d'environnement (`-storepass:env` / `-keypass:env`) : **ils n'apparaissent jamais** dans une ligne de commande, dans les logs ni dans la sortie des scripts. Pour utiliser d'autres valeurs, définissez-les **avant** de lancer les scripts :

```bat
rem Windows (cmd)
set PKI_PASSWORD=mon-mot-de-passe
set PKI_CA_PASSWORD=un-autre-mot-de-passe
```

```powershell
# Windows (PowerShell)
$env:PKI_PASSWORD = "mon-mot-de-passe"
$env:PKI_CA_PASSWORD = "un-autre-mot-de-passe"
```

```bash
# Linux / macOS / Git Bash
export PKI_PASSWORD='mon-mot-de-passe'
export PKI_CA_PASSWORD='un-autre-mot-de-passe'
```

Il faut utiliser le **même** `PKI_PASSWORD` pour vérifier (`verify-pki`) et pour ouvrir les keystores avec `keytool`. Un mot de passe doit comporter au moins 6 caractères (exigence de `keytool` pour PKCS12).

### Générer

```bat
pki\generate-pki.bat
```
```bash
./pki/generate-pki.sh
```

Le script vérifie que `keytool` est disponible (et affiche sa version), puis génère dans l'ordre : la CA, les 4 certificats de service (clé, demande de signature, signature par la CA, import de la CA puis du certificat signé pour obtenir la chaîne), le truststore, les exports PEM publics, les 3 certificats négatifs. Il affiche enfin un **récapitulatif** (artefacts, empreinte SHA-256 de la CA, dates de validité), jamais de mot de passe. Le dossier temporaire `pki/.work` est supprimé automatiquement. Comptez quelques secondes (la clé RSA 4096 de la CA est la plus longue à produire).

**Protection** : si des artefacts existent déjà, le script **refuse de les écraser** (code de sortie 1).

### Vérifier

```bat
pki\verify-pki.bat
```
```bash
./pki/verify-pki.sh
```

Ces enveloppes lancent `java pki/VerifyPki.java` (JDK 17 ou plus, aucune dépendance), que vous pouvez aussi appeler directement. Le programme contrôle les artefacts **avec les API Java** (`KeyStore`, `X509Certificate`, `CertPathValidator`) et affiche un tableau **PASS / FAIL** : fichiers présents, CA (extensions, RSA 4096, autosignée), truststore à une seule entrée, puis pour chaque service le CN exact, l'émetteur, les extensions, le SAN, l'EKU, la validité, la **chaîne validée contre le truststore**, le contenu du keystore (une seule clé privée, chaîne de 2, aucune clé de la CA), enfin les trois certificats négatifs et l'absence de toute clé privée de la CA dans `pki/generated/`.

Code de sortie : `0` = tous les contrôles PASS, `1` = au moins un FAIL, `2` = dossier `pki/generated` introuvable.

### Régénérer

```bat
pki\generate-pki.bat /force
```
```bash
./pki/generate-pki.sh --force
```

L'option supprime **tout** (`pki/generated/` et `pki/ca-private/`) et repart d'un état propre. Cas typiques : certificats proches de l'expiration, changement de mot de passe, soupçon de fuite de la CA. Conséquences à prévoir :

* une **nouvelle CA** est créée : tous les certificats changent, y compris le truststore ;
* il faut **revérifier** (`verify-pki`), puis **recommiter** `pki/generated/` ;
* quand les services utiliseront ces fichiers (étape 7), ils devront être **redémarrés**.

## 6. Inspecter avec `keytool` (commandes à copier)

Depuis la racine du projet. Les chemins sont écrits avec des `/`, que `keytool` accepte aussi sous Windows. Pour les commandes qui ouvrent un keystore, définissez d'abord `PKI_PASSWORD` (voir § 5) :

```bat
set PKI_PASSWORD=ecommerce-demo-pki
```
```bash
export PKI_PASSWORD=ecommerce-demo-pki
```

**La CA** (certificat public) : sujet, `CA:true`, `PathLen:0`, usages, empreintes.

```
keytool -printcert -file pki/generated/ca.crt
```

**Un certificat de service** : sujet, émetteur, SAN, EKU, `CA:false`.

```
keytool -printcert -file pki/generated/order-service/order-service.crt
```

**Le contenu d'un keystore** : 2 entrées, chaîne de longueur 2, SAN, EKU.

```
keytool -list -v -keystore pki/generated/order-service/order-service.p12 -storetype PKCS12 -storepass:env PKI_PASSWORD
```

Pour n'afficher que l'essentiel :

```bat
keytool -list -v -keystore pki/generated/order-service/order-service.p12 -storetype PKCS12 -storepass:env PKI_PASSWORD | findstr /c:"chain length" /c:"Owner" /c:"DNSName" /c:"IPAddress" /c:"serverAuth" /c:"clientAuth"
```
```bash
keytool -list -v -keystore pki/generated/order-service/order-service.p12 -storetype PKCS12 -storepass:env PKI_PASSWORD | grep -E "chain length|Owner|DNSName|IPAddress|serverAuth|clientAuth"
```

Résultat attendu : `Certificate chain length: 2`, `Owner: CN=order-service, …`, `DNSName: localhost`, `DNSName: order-service`, `IPAddress: 127.0.0.1`, `serverAuth`, `clientAuth`.

**Le truststore** : une seule entrée, la CA (`trustedCertEntry`).

```
keytool -list -v -keystore pki/generated/truststore.p12 -storetype PKCS12 -storepass:env PKI_PASSWORD
```

**Un certificat négatif** (par exemple les dates de l'expiré) :

```
keytool -list -v -keystore pki/generated/negative-tests/expired-order-service/expired-order-service.p12 -storetype PKCS12 -storepass:env PKI_PASSWORD
```

## 7. Aperçu : appel `curl` avec certificat client (non exécuté ici)

> **Cet exemple n'a pas été exécuté.** Il ne fonctionnera qu'**à partir de l'étape 7**, quand un service écoutera en HTTPS avec mTLS. Aujourd'hui les services répondent encore en HTTP. Selon le *build* de `curl` (OpenSSL ou Schannel sous Windows), la prise en charge des fichiers PKCS12 peut varier : à confirmer à l'étape 7.

Appel de `/internal/auth/verify` de `user-service` en se présentant comme `authorization-server` :

```bash
curl --cacert pki/generated/ca.crt \
     --cert-type P12 \
     --cert pki/generated/authorization-server/authorization-server.p12:ecommerce-demo-pki \
     -X POST https://localhost:8081/internal/auth/verify \
     -H "Content-Type: application/json" \
     -d @corps.json
```

* `--cacert` : la CA à laquelle `curl` fait confiance pour vérifier le **serveur** (fichier public `ca.crt`).
* `--cert fichier.p12:mot_de_passe` avec `--cert-type P12` : le **certificat client** que `curl` présente au serveur.
* Le nom d'hôte de l'URL (`localhost`, ou `127.0.0.1`) doit figurer dans le SAN du certificat du serveur : c'est le cas.
* Le mot de passe est ici en clair sur la ligne de commande : acceptable pour une démonstration, **à proscrire** en dehors.
* N'utilisez pas `-k` / `--insecure` : il désactiverait justement la vérification que l'on cherche à démontrer.

## 8. Les certificats négatifs et le comportement attendu à l'étape 7

Ils servent aux étapes 7 et 8 pour démontrer les **trois façons dont une connexion peut être rejetée**. **Aucun service ne doit les utiliser en fonctionnement normal.**

| Dossier | Identité | Particularité | Rejet attendu à l'étape 7 | Où ? |
|---|---|---|---|---|
| `rogue-service/` | CN `rogue-service` | signé par la CA du projet, **valide**, mais CN **hors de la liste** des services | la connexion TLS est **acceptée**, puis l'application répond **403** `service_not_allowed` | **applicatif** |
| `expired-order-service/` | CN `order-service` | signé par la CA du projet, **déjà expiré** | la **poignée de main TLS échoue** (certificat expiré) : aucune réponse HTTP | **TLS** |
| `untrusted-order-service/` | CN `order-service` | signé par **une autre CA**, jamais ajoutée au truststore | la **poignée de main TLS échoue** (CA inconnue) : aucune réponse HTTP | **TLS** |

Ce que cela démontre :

* **TLS prouve qui parle, pas ce qu'il a le droit de faire.** `rogue-service` a un certificat parfaitement valide : seule la **table CN → autorisations** de l'application l'arrête.
* Un certificat peut porter le **bon nom** (`order-service`) et être **quand même refusé** : s'il est expiré ou signé par une CA inconnue, le nom ne compte plus. Le CN n'a de valeur que sur un certificat dont la chaîne est valide.
* À l'étape 7, le mode retenu est `client-auth: want` : le serveur **demande** un certificat, mais ne l'exige que sur certains endpoints. Un client **sans** certificat passe donc la poignée de main TLS et est refusé par l'application sur les endpoints protégés ; un client qui présente un certificat **invalide** est refusé dès TLS. Ces comportements sont à **confirmer** à l'étape 7.

`pki/VerifyPki.java` contrôle déjà que ces trois certificats ont la particularité décrite (chaîne valide pour le premier, expiré réellement pour le deuxième, chaîne non valide contre le truststore pour le troisième).

## 9. Limites connues

Cette PKI est une **démonstration pédagogique**, pas une infrastructure de production.

* **Démonstration uniquement.** Ne la réutilisez pas pour protéger de vraies données.
* **Clés privées de services versionnées.** Les keystores `.p12` de `pki/generated/` sont dans Git pour que le correcteur puisse lancer le projet sans régénérer. En production, ils ne le seraient jamais.
* **Mot de passe de démonstration.** `ecommerce-demo-pki` est écrit en clair dans les scripts comme valeur par défaut et protège des clés elles-mêmes versionnées : il n'apporte aucune confidentialité réelle. Utilisez `PKI_PASSWORD` si vous voulez autre chose, en sachant que cela ne protège pas les fichiers déjà commités.
* **Truststore partagé par tous les services.** Chaque service fait confiance à la même CA : un service compromis détenant sa clé ne peut pas se faire passer pour un autre, mais la CA est un point de confiance unique.
* **Aucune révocation** : ni CRL ni OCSP. Un certificat volé reste valable jusqu'à son expiration ; la seule parade est de régénérer toute la PKI.
* **Pas de rotation automatique.** Les certificats de service durent 365 jours : il faut régénérer à la main avant l'échéance (la date figure dans le récapitulatif et dans `verify-pki`).
* **SAN limités** à `localhost`, au nom du service et à `127.0.0.1` (pas `::1`). Un déploiement sur d'autres noms de machine exige de régénérer avec les SAN correspondants.
* **La CA ne doit jamais quitter la machine de génération.** `pki/ca-private/` est exclu du dépôt ; sa perte n'empêche pas d'utiliser les artefacts existants, mais empêche d'en émettre de nouveaux avec la même CA.
* **Production** : les clés seraient gérées par un coffre à secrets (par exemple HashiCorp Vault), un *service mesh* ou **SPIFFE/SPIRE** (identités courtes, rotation automatique), et ne seraient pas versionnées.

## 10. Dépannage

| Symptôme | Cause probable | Solution |
|---|---|---|
| `keytool` n'est pas reconnu, ou `ERREUR : keytool est introuvable` | JDK absent, ou son dossier `bin` n'est pas dans le `PATH` | Installer un JDK 17 ou plus, définir `JAVA_HOME`, ajouter `%JAVA_HOME%\bin` au `PATH`, **rouvrir le terminal** (IntelliJ compris), puis tester `keytool -version`. |
| `keystore password was incorrect`, ou `FAIL … mot de passe PKI_PASSWORD incorrect` | `PKI_PASSWORD` différent de celui utilisé à la génération | Définir le **même** `PKI_PASSWORD` qu'à la génération, ou régénérer avec `/force` (`--force`). |
| `des artefacts PKI existent déjà` | Protection contre l'écrasement | Voulu. Utiliser `/force` (`--force`) pour **tout supprimer** et régénérer. |
| `No subject alternative names matching …` ou `No name matching … found` (à l'étape 7) | Le nom d'hôte utilisé par le client n'est pas dans le SAN du certificat du serveur | Utiliser `localhost`, le nom du service ou `127.0.0.1`, ou régénérer avec le SAN voulu. La vérification porte sur le **SAN**, jamais sur le CN. |
| `certificate_expired`, ou `FAIL … validite` dans `verify-pki` | Certificat de service (365 jours) ou horloge de la machine | Vérifier la date avec `keytool -list -v`, puis régénérer (`/force`) et recommiter `pki/generated/`. |
| `localhost` fonctionne mais pas `[::1]`, ou une autre machine | Le SAN contient `localhost` et `127.0.0.1` en IPv4 seulement | Utiliser `localhost` ou `127.0.0.1` ; sinon régénérer avec les SAN supplémentaires. |
| `ERREUR : dossier introuvable … pki/generated` (code 2) | Les artefacts n'ont pas été générés ou ne sont pas à l'endroit attendu | Lancer d'abord `generate-pki`, ou passer le dossier `pki` en argument à `VerifyPki.java`. |
| `bad interpreter` ou `^M` en lançant un `.sh` | Fins de ligne CRLF dans un script shell | `pki/.gitattributes` impose le LF ; refaire `git checkout -- pki/generate-pki.sh`, ou utiliser `dos2unix`. |
| `Permission denied` en lançant un `.sh` sous Linux | Bit d'exécution absent (Git sous Windows ne le garde pas) | `bash pki/generate-pki.sh`, ou `chmod +x pki/*.sh`, ou `git update-index --chmod=+x pki/*.sh` avant le commit. |
| La fenêtre se ferme aussitôt en double-cliquant sur un `.bat` | Les scripts sont faits pour un terminal | Les lancer depuis le terminal d'IntelliJ ou `cmd`, à la racine du projet. |