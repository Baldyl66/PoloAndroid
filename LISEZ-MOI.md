# Polo Radio — application pour l'autoradio Android

L'interface de l'autoradio de la Polo, en application Android plein écran,
avec la vraie musique, les vrais contacts, les vrais appels et le vrai GPS.

## 1. Fabriquer l'APK (sans rien installer sur ton PC)

1. Crée un compte sur https://github.com (gratuit).
2. Clique sur **New repository**, nomme-le `polo-radio`, choisis **Private**, puis **Create repository**.
3. Sur la page du dépôt : **uploading an existing file**, glisse **tout le contenu** du dossier
   (le dossier `.github` compris), puis **Commit changes**.
   > Astuce : si le dossier `.github` ne passe pas par glisser-déposer, utilise GitHub Desktop.
4. Onglet **Actions** : la construction « Construire l'APK » démarre toute seule (≈ 4 min).
5. Quand elle est verte, clique dessus, en bas **Artifacts → PoloRadio-apk** : tu télécharges un zip
   qui contient `PoloRadio.apk`.

## 1 bis. Brancher ton compte Spotify (à faire une fois)

1. Va sur https://developer.spotify.com/dashboard → **Create app**
   (nom : `Polo Radio`, coche **Web API**). Tu peux aussi réutiliser l'app de ton projet mobile.
2. Dans **Settings → Redirect URIs**, ajoute exactement : `http://127.0.0.1:8765/callback` puis **Save**.
3. Copie le **Client ID** et colle-le dans `app/src/main/assets/www/spotify.json` :
   ```json
   { "clientId": "ton_client_id_ici" }
   ```
   (sinon tu pourras le saisir plus tard dans Réglages → Spotify).
4. Si l'app Spotify est en « Development mode », ton compte doit être celui qui l'a créée
   (ou ajouté dans **User Management**).

Dans l'autoradio : Réglages → Spotify → **Se connecter à Spotify**. La page de connexion s'ouvre dans
le navigateur, tu te connectes (Google, Apple ou e-mail marchent), et tu reviens automatiquement dans Polo Radio.
Aucun mot de passe n'est enregistré dans l'application.

## 2. Installer sur l'autoradio

1. Copie `PoloRadio.apk` sur une clé USB et branche-la sur l'autoradio.
2. Ouvre le gestionnaire de fichiers de l'autoradio, touche le fichier → **Installer**.
   Si Android bloque : autorise « Installer des applications inconnues » pour le gestionnaire de fichiers.
3. Lance **Polo Radio** et **accepte toutes les autorisations** (position, contacts, journal d'appels, téléphone, micro).
4. Un écran « Accès aux notifications » s'ouvre : active **Polo Radio**.
   C'est ce qui permet d'afficher et de piloter Spotify / YouTube Music / Deezer / Bluetooth.

## 3. En faire l'écran d'accueil de l'autoradio

Appuie sur le bouton **Accueil (Home)** de l'autoradio : Android demande quel écran d'accueil utiliser.
Choisis **Polo Radio** puis **Toujours**.
(Sinon : Paramètres → Applications → Applications par défaut → Application d'accueil → Polo Radio.)

Pour revenir à l'écran d'origine : même réglage, choisis l'ancien lanceur.

## 4. Recevoir les notifications du téléphone (Polo Compagnon)

GitHub fabrique aussi **PoloCompagnon.apk** (artefact `PoloCompagnon-apk`) : c'est une petite appli pour **ton téléphone**.

1. Installe-la sur le téléphone, de préférence en USB avec `adb install PoloCompagnon.apk`
   (installée depuis un fichier, Android peut bloquer l'accès aux notifications : « paramètre restreint »).
2. Ouvre **Polo Compagnon** et suis les 3 étapes : accès aux notifications, Bluetooth, code d'appairage.
3. Le code à 6 chiffres est affiché sur l'autoradio : **Réglages → Bluetooth → Téléphone (Polo Compagnon)**.
4. Sur un Xiaomi : mets Polo Compagnon en **Démarrage automatique** et **sans restriction de batterie**.

La liaison passe par le **partage de connexion** du téléphone (Wi-Fi), sinon par le **Bluetooth**.

## Ce qui fonctionne pour de vrai

| Fonction | Comment |
|---|---|
| Musique | Titre, artiste, pochette, avancement et commandes de l'appli de musique en cours (Spotify, YouTube Music, Deezer, Bluetooth…). « Sources » ouvre tes applis de musique |
| Spotify | Ton compte : playlists, titres likés, écoutés récemment, recherche de tout le catalogue (titres, artistes, albums, playlists, podcasts), fiches artiste / album / podcast, bouton « + » pour ajouter à la file, vraie file « À suivre », aléatoire / boucle, choix de l'appareil |
| Contacts | Contacts synchronisés par le Bluetooth de l'autoradio (favoris, sinon les plus appelés) |
| Appels | Journal d'appels réel ; Appeler, Rappeler, le clavier et la Messagerie lancent de vrais appels |
| GPS | Antenne GPS de l'autoradio, vraie carte, vrais itinéraires, éviter péages / autoroutes |
| Recherche | N'importe quelle adresse ou lieu en France, à la voix ou au clavier |
| Maison / Travail | À définir dans Réglages → Navigation (ou en touchant le bouton la 1re fois) |
| Trajets récents | Enregistrés automatiquement |
| Météo | Vraie météo à ta position (Open-Meteo) |
| Notifications | Toutes les notifications du téléphone (via Polo Compagnon) : bandeau animé en haut de l'écran, écran Notifications, dernière notification sur l'accueil, réponses proposées selon le message |
| Assistant vocal | Reconnaissance vocale d'Android (destination, réponse aux messages) et lecture à voix haute. Un message reçu est lu (petit son, puis « Dylan, tu as un nouveau message de Paul sur WhatsApp… Tu veux répondre ? »), la réponse se dicte et se confirme à la voix. La voix sert partout (messages, assistant, GPS) : celle de Marmalade TTS (voix Kokoro, hors ligne) s’il est choisi comme moteur vocal dans Android, sinon la voix française par défaut de Google (« Synthèse vocale Google ») ; sans l’un des deux, l’appli reste muette et affiche seulement le texte |
| Assistant | Bouton « Assistant » dans la barre du bas, ou dire « Polo » (réveil hors ligne, réglable dans Réglages → Alertes → Voix ; le modèle vocal est ajouté par GitHub Actions). Commandes hors ligne : « emmène-moi à la maison », « trouve une station essence », « à quelle heure j'arrive ? », « arrête le guidage », « quel temps fait-il ? », « il va pleuvoir ? », « quelle heure est-il ? », « appelle Paul », « lis mes messages », « mets la musique », « pause », « suivant », « monte le son », « volume à 30 », « ouvre la météo », « que sais-tu faire ? ». Il te tutoie |
| Mémoire de l'assistant | Tout reste dans l'autoradio. « Comment je m'appelle ? », « appelle-moi Dyl », « retiens que ma couleur préférée est le bleu », « quelle est ma couleur préférée ? », « mon anniversaire est le 12 mars », « l'anniversaire de Maman est le 3 juin », « qu'est-ce que tu sais sur moi ? », « oublie ma couleur préférée », « oublie tout ». Au démarrage (réglable : Saluer au démarrage), il te salue, souhaite les anniversaires du jour et propose une chose : appeler la personne fêtée, ton trajet habituel à cette heure (après 3 fois), ou remettre ta musique. Il remet aussi ton volume habituel s'il est revenu tout en bas ou tout en haut |
| Infos du téléphone | Avec les autorisations Contacts, Agenda et SMS de Polo Compagnon : anniversaires des contacts (avec l'âge), rendez-vous du jour et du lendemain, prochain réveil, batterie. Au démarrage, en une phrase : « Salut Dylan ! Aujourd'hui tu as un rendez-vous au dentiste à 14 heures 30 et Paul fête ses 36 ans, est-ce que tu veux l'appeler ou lui envoyer un message ? ». Messages dictés envoyés par le téléphone : « envoie un message à Paul », « dis à Paul que je suis en retard » (avec confirmation). Questions : « qu'est-ce que j'ai aujourd'hui / demain ? », « c'est l'anniversaire de qui ? », « les anniversaires de la semaine », « quand est l'anniversaire de Paul ? », « à quelle heure sonne mon réveil ? », « la batterie de mon téléphone ». Alerte si le téléphone passe sous 15 % sans être en charge |
| Son & écran | Volume média, volume des appels et luminosité réels ; raccourcis vers les réglages Bluetooth et son |
| Prénom | Réglages → À propos → Conducteur (affiché au démarrage) |
| Guidage vocal | Annonces « Dans 300 mètres, tournez à droite… » ; bouton haut-parleur sur la carte pour couper ; Réglages → Navigation |
| Musique pendant les annonces | Baisse ou se met en pause (Réglages → Son), puis revient |
| Musique pendant les appels | Se met en pause pendant l'appel et reprend après |
| Limitation de vitesse | Vraie limite OpenStreetMap là où elle est renseignée ; la vitesse passe en rouge si tu dépasses |
| Démarrage automatique | Réglages → Affichage → « Lancer au démarrage de la voiture » |

## Démarrage automatique

1. Garde l'option **Lancer au démarrage de la voiture** activée (Réglages → Affichage).
2. Android ouvre l'écran **« Afficher par-dessus les autres applis »** : active-le pour Polo Radio
   (sans ça, Android bloque le lancement automatique).
3. Choisis aussi Polo Radio comme **écran d'accueil** : beaucoup d'autoradios ne s'éteignent pas vraiment
   (mise en veille), et au réveil c'est l'écran d'accueil qui revient.

## Limites connues

- **Contacts / appels** : sur certains autoradios chinois (FYT, TS10, TS18…), le Bluetooth du téléphone
  est géré par une appli maison qui ne partage pas toujours ses contacts avec Android. Si la liste reste vide,
  dis-le : il faudra un réglage propre à ton modèle (envoie une photo de *Paramètres → À propos*).
- **Décrocher / raccrocher** : l'appli essaie dans l'ordre le bouton de la notification d'appel, Android, puis la touche « décrocher ». Si rien ne marche sur ton modèle, utilise les boutons de l'autoradio et dis-le.
- **Pause de la musique pendant un appel** : détectée via l'état téléphone / audio de l'autoradio ; sur les modèles
  dont l'appli Bluetooth ne le signale pas, l'appli de musique gère elle-même la pause.
- **Limitation de vitesse** : affichée seulement quand elle est renseignée dans OpenStreetMap.
- **Messages** : viennent des notifications du téléphone via Polo Compagnon. Aucune des deux applis n'a accès aux SMS : la réponse passe par le bouton « Répondre » de la notification sur le téléphone, et seulement si l'appli en propose un.
- **Bluetooth du compagnon** : sur certains autoradios chinois, le Bluetooth du téléphone est géré par une puce à part qu'Android ne voit pas ; la liaison passe alors uniquement par le partage de connexion.
- **Assistant vocal** : la dictée nécessite les services Google (ou un autre moteur de reconnaissance vocale) sur l'autoradio
  et une connexion internet. Pour entendre l'appli, installe « Synthèse vocale Google » (Speech Services by Google), ou Marmalade TTS (F-Droid, Android 9 minimum) :
  dans Marmalade, télécharge le moteur Kokoro et choisis la voix française, puis Paramètres Android → Synthèse vocale → moteur préféré → Marmalade. Sans l'un des deux, tout reste muet.
- Il faut une connexion internet (partage de connexion du téléphone) pour la carte, les itinéraires, la recherche et la météo.

## Fabriquer l'APK sur le PC

Dans le dossier du projet : `./gradlew assembleDebug`. L'APK est dans `app/build/outputs/apk/debug/app-debug.apk`.

## Mettre à jour l'interface

Remplace `app/src/main/assets/www/index.html` par la nouvelle version de `autoradio-polo.html`,
renomme-la `index.html`, envoie-la sur GitHub : un nouvel APK est construit automatiquement.
