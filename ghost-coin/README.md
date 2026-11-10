# Ghost Coin

Jeu Android de machine à sous, de construction de villages, d'attaques, de raids et de collection de cartes, dans un univers de fantômes. Tous les graphismes et tous les sons sont générés par le code : aucune ressource tierce.

## Organisation

| Dossier | Rôle |
|---|---|
| `core/` | Règles du jeu en Kotlin pur : une fonction `(état, action, heure, hasard) → (nouvel état, événements)`. Partagé par l'application et le serveur. |
| `server/` | Serveur autoritaire (HTTP + JSON, sans dépendance externe). Comptes, validation de toutes les actions, classement, amis, cadeaux, échange de cartes. |
| `app/` | Application Android (Kotlin, Jetpack Compose). |

## Compiler et tester

Il faut un JDK 21 et le SDK Android (plateforme 35).

```
./gradlew :core:test :server:test :app:testDebugUnitTest   # tous les tests
./gradlew :app:assembleDebug                               # APK de test
./gradlew :app:bundleRelease                               # AAB (voir « Signature »)
```

Les tests de l'application rendent chaque écran en PNG dans `app/build/shots/`.

## Modes de jeu

- **Solo** (par défaut) : le moteur tourne sur le téléphone, contre des adversaires simulés. Sauvegarde automatique locale.
- **En ligne** : dans Social, saisir l'adresse d'un serveur puis créer un compte. Le téléphone n'envoie alors que des intentions ; rotations, gains, coûts, attaques et raids sont calculés par le serveur. Sans réseau, le jeu reste consultable mais aucune action n'est acceptée. L'adresse doit être en `https://` (Android bloque le HTTP en clair).

## Lancer le serveur

```
./gradlew :server:fatJar
PORT=8080 DATA_DIR=./data java -jar server/build/libs/server-all.jar
```

ou avec Docker, depuis ce dossier : `docker build -f server/Dockerfile -t ghost-coin-server .`

À configurer chez l'hébergeur : un disque persistant monté sur `DATA_DIR` (un fichier JSON par compte) et le HTTPS, à terminer devant le serveur. Aucun secret n'est stocké dans l'application ; le serveur ne garde que l'empreinte SHA-256 des jetons de session et des codes de récupération.

Protections en place : jeton de session par compte, numéro de séquence contre le rejeu, limitation à 12 requêtes par seconde et 300 par minute par compte, 10 créations de compte par heure et par adresse IP, raid plafonné au quart des pièces de la cible, cible récemment touchée épargnée pendant 10 minutes, seuls les doubles de cartes s'échangent (5 par jour).

## Signature

`bundleRelease` signe l'AAB si un fichier `keystore.properties` (non versionné) est présent à la racine :

```
storeFile=upload-key.jks
storePassword=…
keyAlias=ghostcoin
keyPassword=…
```

## Tour du monde

196 pays dans l'ordre Afrique (54), Europe (46), Amérique (35), Asie (47), Océanie (14), en commençant par le Sénégal. Les données sont dans `core/.../World.kt`, une ligne par pays :

```
code ISO | nom | latitude | longitude | biome | style | monument | lieu emblématique
```

Ajouter un pays revient à ajouter une ligne. Le biome choisit le paysage (8 possibles), le style l'architecture (14), le monument sa silhouette (26 formes). Chaque fin de continent donne une récompense et déclenche une cinématique.

## Ghost Admin

Appui long sur le profil, en haut à gauche de l'écran d'accueil.

- Le mot de passe se définit sur le serveur, dans la variable d'environnement `ADMIN_PASSWORD` (12 caractères minimum). Sans elle, l'administration est désactivée. Il n'est ni dans le dépôt ni dans l'application.
- Chaque commande est vérifiée par le serveur : session administrateur distincte des sessions de joueurs, expirée après 15 minutes d'inactivité, 5 essais de mot de passe par quart d'heure et par adresse IP.
- Toutes les actions sont écrites dans `audit.log` (dossier de données). La dernière modification d'un joueur peut être annulée.
- Les comptes de test portent le préfixe `TEST-` et l'indicateur `test` ; personne ne peut s'y connecter.

**GHOST SANDBOX** est une partie d'essai enregistrée dans un fichier séparé (`sandbox.json`) : elle ne touche ni la partie solo ni le serveur. Dans l'APK de test (debug) elle s'ouvre sans serveur ; dans l'APK publié (release) il faut une session administrateur.

## Ce qui n'est pas fait

- Achats en argent réel (Google Play Billing), publicités récompensées et pass saisonnier : ils demandent un compte Play Console et une régie, absents ici. La boutique ne vend que des coffres contre des pièces du jeu.
- Projet Unity : le jeu est en Kotlin natif.
- Défis entre amis, cosmétiques payants.
- Un décor dessiné à la main par pays : les villages sont composés à partir du biome, du style et du monument de chaque pays. Les rattachements culturels et les coordonnées sont à faire relire avant publication.
- Les triches de jeu (résultat imposé, ressources illimitées) n'existent que dans le bac à sable local, pas sur un compte en ligne.
- Le serveur stocke ses données dans des fichiers : suffisant pour un test, à remplacer par une base de données avant une large audience.
