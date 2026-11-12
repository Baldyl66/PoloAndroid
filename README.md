# Polo Radio

Interface plein écran pour **autoradio Android**, avec un assistant vocal en français.
Elle remplace l'écran d'accueil de l'autoradio et regroupe la musique, les appels, le GPS, la météo
et les notifications du téléphone. Elle a été pensée pour l'écran 1024 × 600 d'une VW Polo, mais fonctionne
sur la plupart des autoradios Android.

Le dépôt contient deux applications :

| Application | Où l'installer | Rôle |
|---|---|---|
| **Polo Radio** (`app/`) | Autoradio | Interface, musique, appels, GPS, assistant vocal |
| **Polo Compagnon** (`compagnon/`) | Téléphone | Envoie à l'autoradio les notifications, l'agenda, les anniversaires et la batterie du téléphone |

## Fonctionnalités

| Fonction | Détail |
|---|---|
| Musique | Titre, artiste, pochette et commandes de l'appli de musique en cours (Spotify, YouTube Music, Deezer, Bluetooth…) |
| Spotify | Compte relié : playlists, titres likés, écoutés récemment, recherche dans tout le catalogue, fiches artiste / album / podcast, file « À suivre », aléatoire / boucle, choix de l'appareil |
| Contacts et appels | Contacts synchronisés par le Bluetooth de l'autoradio, journal d'appels, clavier, messagerie |
| GPS | Antenne GPS de l'autoradio, carte, itinéraires, éviter péages / autoroutes, adresses Maison et Travail, trajets récents |
| Guidage vocal | Annonces « Dans 300 mètres, tourne à droite… », bouton haut-parleur sur la carte pour couper |
| Limitation de vitesse | Limite OpenStreetMap lorsqu'elle est renseignée ; la vitesse passe en rouge en cas de dépassement |
| Météo | Météo à la position actuelle (Open-Meteo) |
| Notifications | Notifications du téléphone via Polo Compagnon : bandeau animé, écran dédié, réponses proposées |
| Son et écran | Volume média, volume des appels, luminosité ; la musique baisse ou se met en pause pendant les annonces et les appels |
| Démarrage automatique | L'application se lance au démarrage de la voiture (Réglages → Affichage) |

### Assistant vocal

- **Activation** : bouton « Assistant » dans la barre du bas, ou dire **« Polo »**. La détection du mot « Polo »
  fonctionne hors ligne (Vosk) et se règle dans Réglages → Alertes → Voix.
- **Dictée** : reconnaissance vocale d'Android (destination, réponse aux messages, questions).
- **Commandes** : « emmène-moi à la maison », « trouve une station essence », « à quelle heure j'arrive ? »,
  « arrête le guidage », « quel temps fait-il ? », « appelle Camille », « lis mes messages », « mets la musique »,
  « suivant », « volume à 30 », « que sais-tu faire ? »… L'assistant tutoie.
- **Messages** : un message reçu est lu (« Tu as un nouveau message de Camille sur WhatsApp… Tu veux répondre ? »),
  la réponse se dicte puis se confirme à la voix.
- **Mémoire** : « appelle-moi Alex », « retiens que ma couleur préférée est le bleu », « mon anniversaire est le 12 mars »,
  « qu'est-ce que tu sais sur moi ? », « oublie tout ». Tout reste stocké dans l'autoradio.
- **Accueil au démarrage** (désactivable) : salutation, rendez-vous du jour, anniversaires, puis une proposition
  (appeler la personne fêtée, lancer le trajet habituel ou remettre la musique).
- **Intelligence (facultatif)** : avec une clé Gemini gratuite (Réglages → Alertes), l'assistant répond aussi aux
  questions libres. Sans clé, il ne comprend que les commandes prévues.

### Voix de l'application

L'application parle avec le moteur de synthèse vocale d'Android, sans voix embarquée :

1. **Marmalade TTS**, s'il est choisi comme moteur vocal préféré dans Android ;
2. sinon **Synthèse vocale Google** (Speech Services by Google), avec sa voix française par défaut ;
3. sinon l'application reste **muette** : les phrases s'affichent le temps de les lire.

Pour une voix plus naturelle, hors ligne et gratuite, avec [Marmalade TTS](https://f-droid.org/packages/app.marmalade.tts/) (F-Droid, Android 9 minimum) :

1. installer Marmalade TTS ;
2. dans Marmalade, télécharger le moteur **Kokoro** et choisir la voix française ;
3. Paramètres Android → Synthèse vocale → moteur préféré → **Marmalade**.

Pour revenir à la voix Google, il suffit de remettre Google comme moteur préféré : l'application suit sans réinstallation.

## Installation

### 1. Récupérer les APK

- **Le plus simple** : dans les [Releases](../../releases), « Dernière version » contient `PoloRadio.apk` et `PoloCompagnon.apk`.
- **Avec votre propre copie** : faites un *fork* du dépôt. À chaque envoi sur `main`, GitHub Actions construit les deux APK
  (onglet **Actions** → artefacts `PoloRadio-apk` et `PoloCompagnon-apk`) et les publie dans les Releases.
- **Sur le PC** : `./gradlew assembleDebug`. Le réveil « Polo » demande en plus le modèle Vosk français dans
  `app/src/main/assets/vosk-fr` (voir l'étape correspondante dans `.github/workflows/build.yml`).

**Signature (facultatif)** : sans clé fixe, chaque construction est signée différemment et Android refuse d'installer
une mise à jour par-dessus l'ancienne version. Pour l'éviter, ajoutez dans votre fork les secrets GitHub
`POLO_KEYSTORE_B64` (le fichier `.keystore` encodé en base64, alias `polo`) et `POLO_KEYSTORE_PASSWORD`.

### 2. Installer sur l'autoradio

1. Copier `PoloRadio.apk` sur une clé USB et l'ouvrir depuis le gestionnaire de fichiers de l'autoradio → **Installer**.
   Si Android bloque, autoriser « Installer des applications inconnues » pour le gestionnaire de fichiers.
2. Lancer **Polo Radio** et **accepter toutes les autorisations** (position, contacts, journal d'appels, téléphone, micro).
3. Dans l'écran « Accès aux notifications », activer **Polo Radio** : c'est ce qui permet de piloter les applis de musique.
4. Appuyer sur **Accueil** et choisir **Polo Radio** → **Toujours** pour en faire l'écran d'accueil.
   Beaucoup d'autoradios se mettent seulement en veille : au réveil, c'est l'écran d'accueil qui revient.
5. Pour le démarrage automatique, laisser l'option **Lancer au démarrage de la voiture** activée et autoriser
   **« Afficher par-dessus les autres applis »** quand Android le demande.

### 3. Relier Spotify (facultatif)

1. Sur le [tableau de bord Spotify](https://developer.spotify.com/dashboard), créer une app (cocher **Web API**).
2. Dans **Settings → Redirect URIs**, ajouter exactement `http://127.0.0.1:8765/callback`.
3. Mettre le **Client ID** dans `app/src/main/assets/www/spotify.json` (`{ "clientId": "…" }`),
   ou le saisir plus tard dans Réglages → Spotify.
4. Si l'app Spotify est en « Development mode », le compte utilisé doit être celui qui l'a créée
   ou être ajouté dans **User Management**.

Puis, dans l'autoradio : Réglages → Spotify → **Se connecter à Spotify**. La connexion se fait dans le navigateur,
puis l'application reprend la main. Aucun mot de passe n'est enregistré.

### 4. Installer Polo Compagnon sur le téléphone (facultatif)

1. Installer `PoloCompagnon.apk` sur le téléphone, de préférence avec `adb install PoloCompagnon.apk`
   (installé depuis un fichier, Android peut bloquer l'accès aux notifications : « paramètre restreint »).
2. Ouvrir **Polo Compagnon** et suivre les 3 étapes : accès aux notifications, Bluetooth, code d'appairage.
3. Le code à 6 chiffres s'affiche sur l'autoradio : **Réglages → Bluetooth → Téléphone (Polo Compagnon)**.
4. Sur certains téléphones (Xiaomi notamment), autoriser le **démarrage automatique** et retirer les
   **restrictions de batterie**.

La liaison passe par le **partage de connexion** du téléphone (Wi-Fi), sinon par le **Bluetooth**.
Avec les autorisations Contacts, Agenda et SMS, le compagnon donne aussi à l'assistant les anniversaires,
les rendez-vous, le prochain réveil et la batterie, et permet d'envoyer des messages dictés.

## Limites connues

- **Contacts et appels** : sur certains autoradios (FYT, TS10, TS18…), le Bluetooth du téléphone est géré par une appli
  du fabricant qui ne partage pas toujours ses contacts avec Android. La liste peut alors rester vide.
- **Décrocher / raccrocher** : l'application essaie le bouton de la notification d'appel, Android, puis la touche
  « décrocher ». Sur certains modèles, il faut utiliser les boutons de l'autoradio.
- **Pause pendant les appels** : détectée par l'état téléphone / audio de l'autoradio ; si le modèle ne le signale pas,
  c'est l'appli de musique qui gère la pause.
- **Messages** : ils viennent des notifications du téléphone. La réponse passe par le bouton « Répondre » de la notification,
  seulement si l'appli d'origine en propose un.
- **Bluetooth du compagnon** : sur certains autoradios, la puce Bluetooth n'est pas visible par Android ; la liaison passe
  alors uniquement par le partage de connexion.
- **Dictée** : nécessite les services Google (ou un autre moteur de reconnaissance vocale) et une connexion internet.
- **Internet** : nécessaire pour la carte, les itinéraires, la recherche et la météo (le partage de connexion du téléphone suffit).

## Structure du projet

| Chemin | Contenu |
|---|---|
| `app/src/main/assets/www/index.html` | Toute l'interface (HTML, CSS, JavaScript) |
| `app/src/main/java/fr/dylan/polo/` | Partie Android : pont JavaScript, médias, téléphone, GPS, voix, réveil « Polo » |
| `compagnon/` | Application du téléphone |
| `.github/workflows/build.yml` | Construction des APK par GitHub Actions |
