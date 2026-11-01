# Agropama Messagerie

Messagerie interne de type WhatsApp pour les employés d'**Agropama**.
Les employés s'inscrivent eux-mêmes, mais un **administrateur doit valider chaque compte** avant qu'il puisse se connecter.

## Fonctionnalités

**Employés**
- Inscription (nom, courriel, téléphone, service, poste, mot de passe) → compte « en attente »
- Connexion uniquement une fois le compte validé
- Discussions individuelles et **groupes** (création, ajout/retrait de membres, quitter)
- Messages **en temps réel** (Socket.IO), indicateur « écrit… », présence en ligne / vu à
- Accusés de lecture (✓ envoyé, ✓✓ bleu lu), compteur de non-lus
- Envoi de **fichiers et photos** (15 Mo max, accessibles uniquement aux membres de la discussion)
- Suppression de ses messages, liens cliquables, notifications du navigateur
- Profil modifiable (coordonnées, mot de passe)
- Interface responsive (ordinateur et téléphone), thème clair/sombre automatique

**Administrateurs**
- Tableau de bord : comptes en attente, actifs, en ligne, nombre de discussions et de messages
- Notification en direct à chaque nouvelle inscription (pastille rouge)
- **Valider / refuser** les inscriptions
- **Suspendre / réactiver** un compte (déconnexion immédiate de l'employé)
- Nommer ou retirer un administrateur
- Réinitialiser un mot de passe (mot de passe temporaire généré)
- Supprimer un compte
- Supprimer n'importe quel message

## Démarrage

Prérequis : **Node.js 22.13 ou plus récent** (la base SQLite est intégrée à Node, rien d'autre à installer).

```bash
cd agropama-chat
npm install
ADMIN_EMAIL=admin@agropama.com ADMIN_PASSWORD='un-mot-de-passe-solide' npm start
```

Ouvrir ensuite http://localhost:3000.

Au premier lancement, un compte administrateur est créé avec `ADMIN_EMAIL` / `ADMIN_PASSWORD`.
Si `ADMIN_PASSWORD` n'est pas fourni, un mot de passe aléatoire est généré et affiché dans la console.

### Variables d'environnement

| Variable          | Rôle                                                        | Défaut                    |
|-------------------|-------------------------------------------------------------|---------------------------|
| `PORT`            | Port HTTP                                                   | `3000`                    |
| `DATA_DIR`        | Dossier de la base de données et des fichiers envoyés       | `./data`                  |
| `ADMIN_EMAIL`     | Courriel du premier administrateur                          | `admin@agropama.com`      |
| `ADMIN_PASSWORD`  | Mot de passe du premier administrateur                      | généré aléatoirement      |
| `ADMIN_NAME`      | Nom affiché du premier administrateur                       | `Administrateur Agropama` |
| `JWT_SECRET`      | Clé de signature des sessions                               | générée dans `DATA_DIR`   |
| `SECURE_COOKIES`  | `true` si l'application est servie en HTTPS (recommandé)    | `false`                   |

## Mise en production

- Servir l'application derrière un proxy HTTPS (Nginx, Caddy…) et définir `SECURE_COOKIES=true`.
  Le proxy doit laisser passer les WebSockets (`Upgrade` / `Connection`).
- Sauvegarder régulièrement le dossier `DATA_DIR` (base `agropama.db` + `uploads/`).
- Changer le mot de passe administrateur après la première connexion (menu « Mon profil »).

## Tests

```bash
npm test
```

Le test d'intégration couvre : inscription → connexion refusée tant que le compte est en attente →
validation par l'admin → discussion et message en temps réel → non-lus → groupe → suspension avec déconnexion immédiate.

## Structure

```
agropama-chat/
├── src/
│   ├── server.js   # point d'entrée
│   ├── app.js      # API REST, administration, Socket.IO
│   └── db.js       # schéma SQLite
├── public/         # interface web (HTML/CSS/JS sans framework)
└── test/           # tests d'intégration
```

## Application Android (APK)

Le fichier **`Agropama.apk`** (à la racine de ce dossier) s'installe directement sur les téléphones Android (Android 6 ou plus récent).

**Installation sur un téléphone**
1. Copier `Agropama.apk` sur le téléphone (WhatsApp, courriel, câble USB, Google Drive…) et l'ouvrir.
2. Android demande d'autoriser l'installation d'applications de cette source : accepter.
3. L'application se connecte automatiquement au serveur d'essai `https://agropama-messagerie.onrender.com`
   (adresse définie dans `mobile/www/app.js`, `DEFAULT_SERVER`). On peut la changer dans « Mon profil » → « Changer de serveur ».

> L'APK est l'application que les employés installent ; elle se connecte au **serveur** décrit plus haut,
> qui doit être en ligne et accessible depuis les téléphones (voir « Mise en production »).
> Pour un essai sur le réseau Wi-Fi du bureau, on peut saisir l'adresse locale de l'ordinateur qui fait tourner le serveur,
> par exemple `http://192.168.1.20:3000`.

**Reconstruire l'APK** (Node.js, Java 21 et le SDK Android requis)

```bash
cd agropama-chat/mobile
npm install
ANDROID_HOME=/chemin/vers/android-sdk npm run apk
# → android/app/build/outputs/apk/debug/app-debug.apk
```

L'APK fourni est signé avec une clé de développement : il s'installe sans problème en dehors du Play Store.
Pour une publication sur le Google Play Store, il faudra générer une version signée avec une clé propre à Agropama.
