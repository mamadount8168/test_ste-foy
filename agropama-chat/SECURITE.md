# Sécurité d'Agropama Messagerie

Ce document résume l'audit de sécurité (tests d'intrusion), les corrections apportées et les
limites qui subsistent. Aucun logiciel n'est « impénétrable » : l'objectif est d'appliquer les
meilleures pratiques connues et de vérifier par des attaques réelles que les failles connues sont fermées.

## 1. Résultat des tests d'intrusion

Le script `pentest/attaque.js` lance 26 attaques contre un serveur (comptes de test créés puis supprimés) :

```bash
node pentest/attaque.js https://adresse-du-serveur admin@agropama.com 'mot-de-passe-admin'
```

| Version | Attaques bloquées | Vulnérabilités |
|---|---|---|
| Avant l'audit | 14 / 25 | **11** |
| Après les corrections | **26 / 26** | **0** |

### Vulnérabilités trouvées puis corrigées

| # | Attaque | Gravité | Correction |
|---|---|---|---|
| A12 | Vol de la base : messages lisibles en clair | Critique | Chiffrement de bout en bout : le serveur ne stocke que du chiffré |
| A13 | Envoi de messages non chiffrés | Critique | Le serveur refuse tout message ou fichier non chiffré |
| A16 | Session encore valide après réinitialisation du mot de passe | Élevée | Sessions révocables stockées en base (empreinte SHA-256 du jeton) |
| A17 | Autres appareils connectés après changement de mot de passe | Élevée | Changement de mot de passe = déconnexion des autres appareils |
| A15 | Cookie volé valable 7 jours après déconnexion | Élevée | La déconnexion détruit la session côté serveur |
| A19 | Session d'un compte suspendu réactivée automatiquement | Moyenne | Suspension/refus = toutes les sessions supprimées |
| A20 | Connexion temps réel depuis un site pirate | Moyenne | Vérification de l'origine des connexions WebSocket |
| A21 | Requête modifiante depuis un site pirate (CSRF) | Moyenne | Vérification de l'en-tête `Origin` + cookie `SameSite=Strict` |
| A22 | En-têtes de sécurité absents | Moyenne | CSP stricte, HSTS, Permissions-Policy, COOP, CORP, `no-referrer` |
| A23 | Découverte des comptes par le temps de réponse | Faible | Comparaison bcrypt systématique (compte fictif) ; inscription : réponse identique |
| A25 | Mot de passe « 12345678 » accepté | Moyenne | 10 caractères min., lettres + chiffres, mots de passe courants refusés |
| — | Force brute | Moyenne | Verrouillage du compte 15 min après 5 échecs + limite par adresse IP |
| — | Inondation d'événements | Faible | Limite de 150 événements / 10 s, déconnexion au-delà de 400 |
| — | APK « debug » (débogable, données extractibles par câble) | Élevée | APK de production signée, non débogable |
| — | Android : connexions non chiffrées et sauvegardes autorisées | Moyenne | HTTPS obligatoire, sauvegardes et transferts désactivés |
| — | Signalisation des appels modifiable par le serveur | Moyenne | Descriptions d'appel signées et vérifiées (anti-interception) |

Déjà protégé avant l'audit : élévation de privilèges, accès sans connexion, accès aux discussions
d'autrui (IDOR), injection SQL, traversée de répertoires, fichiers piégés, jetons falsifiés.

## 2. Chiffrement de bout en bout

Même principe que Signal / WhatsApp, avec les primitives standard du navigateur (WebCrypto) :

- **Identité** : chaque employé crée sur son téléphone deux paires de clés P-256
  (ECDH pour le chiffrement, ECDSA pour la signature). Les clés privées sont gardées dans
  le stockage de l'appareil sous forme **non exportable**.
- **Mot de passe** : il ne quitte jamais l'appareil. Le serveur reçoit une empreinte PBKDF2
  (100 000 itérations), puis la stocke hachée avec bcrypt.
- **Sauvegarde des clés** (pour changer de téléphone) : chiffrée en AES-256-GCM avec une clé
  dérivée du mot de passe par PBKDF2 (310 000 itérations, sel aléatoire). Le serveur ne peut pas l'ouvrir.
- **Messages, photos, vidéos, messages vocaux, documents** : clé AES-256-GCM aléatoire par message,
  transmise à chaque destinataire via ECDH éphémère + HKDF-SHA-256, puis l'ensemble est **signé** par l'expéditeur.
  Un message modifié, déplacé dans une autre discussion ou attribué à quelqu'un d'autre est rejeté.
- **Appels vocaux et vidéo** : chiffrés de pair à pair par WebRTC (DTLS-SRTP). La description de l'appel
  (qui contient l'empreinte de la clé DTLS) est signée par l'appelant : un serveur compromis ne peut pas s'interposer.
- **Vérification d'identité** : un **code de sécurité** de 60 chiffres est affiché dans les informations
  de chaque discussion. Le comparer de vive voix garantit qu'aucune clé n'a été substituée.
  L'application mémorise les clés de chaque collègue et **avertit si elles changent**.

Ce que le serveur voit encore (comme WhatsApp) : qui écrit à qui et quand, la taille des messages,
les noms de groupes, les comptes et la durée des appels.

## 3. Limites et recommandations

1. **Dépôt GitHub public.** Le code est visible de tous. Cela ne crée pas de faille en soi
   (aucun secret n'y figure), mais il est recommandé de **rendre le dépôt privé**.
2. **Hébergement gratuit (Render Free)** : les données sont effacées à chaque mise en veille ou mise à jour.
   Pour l'utilisation réelle : offre payante avec disque persistant et sauvegardes régulières.
3. **Code servi par le serveur** : l'application Android charge l'interface depuis le serveur.
   Si le serveur était entièrement compromis, il pourrait envoyer une version modifiée de l'application.
   Pour supprimer ce risque, il faudra intégrer l'interface directement dans l'APK.
4. **Mot de passe oublié** : un administrateur peut réinitialiser le mot de passe, mais les anciens messages
   de l'employé deviennent illisibles (c'est le prix du chiffrement de bout en bout).
5. **Vérifier les codes de sécurité** avec les collègues, surtout après un avertissement de changement de clé.
6. **Clé de signature de l'APK** : à conserver précieusement (hors du dépôt). Sans elle, les mises à jour
   de l'application ne pourront pas s'installer par-dessus la version existante.
7. Améliorations possibles : double authentification (TOTP) pour les administrateurs, serveur TURN dédié,
   confidentialité persistante (rotation des clés à la manière du « Double Ratchet » de Signal).
