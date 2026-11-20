# Bronet Dictée — Android (Samsung Galaxy Tab A11+) — révision 158

Réécriture native de Bronet Dictée pour Android (la version Mac utilise MLX/PySide6, propres à Apple Silicon).
Tout se pilote **à l'écran** : trois touches « maintenir pour parler » en bas, toujours visibles au-dessus du clavier virtuel.

| Mac | Android |
|---|---|
| Ctrl — dicter | **1 · DICTER** (maintenir, parler, relâcher) |
| Opt gauche — traduire | **2 · TRADUIRE** (direction choisie dans les menus, ⇄ pour inverser) |
| Cmd gauche — demander à l'IA | **3 · DEMANDER À L'IA** (ChatGPT) |
| Conversation / traductions | Bouton **Conversation** : deux touches, une par personne, lecture vocale |
| Mode GPT / Compte ChatGPT | Interrupteur **Mode GPT** + bouton **Compte ChatGPT** |
| Collage au curseur | Texte dans l'application + **copie automatique** (colle ensuite où tu veux) |

Langues : français (+ Montréal), anglais (Canada, Ontario, É.-U., R.-U.), tagalog (Batangas), espagnol (Guatemala, Espagne, Mexique), allemand (Suisse écrit, Allemagne), vietnamien, malais, thaï, indonésien, japonais, coréen, mandarin, portugais, italien, arabe. Orthographe suisse (ß → ss) appliquée.

## Obtenir l'APK sans PC (GitHub + Downloader)
1. Crée un compte gratuit sur github.com, puis un **nouveau dépôt public** (ex. `bronet-android`).
2. Dépose tout le contenu de ce dossier dans le dépôt (Add file → Upload files), y compris le dossier caché `.github`.
3. Onglet **Actions** → « Compiler l'APK Android » → s'il est en pause, active-le, puis **Run workflow**. Attends ~5 minutes (coche verte).
4. Le lien de téléchargement est :
   `https://github.com/TON-COMPTE/bronet-android/releases/latest/download/Bronet-Dictee.apk`
5. Sur la tablette : ouvre **Downloader**, colle ce lien, télécharge, puis **Installer**. Autorise « Installer des applis inconnues » pour Downloader si demandé.

(Alternative : Android Studio → ouvrir ce dossier → Build → Build APK.)

## Premier lancement
- Autorise le **microphone**.
- Dictée : si la langue n'est pas reconnue hors ligne, télécharge le pack dans Réglages › Gestion générale › Langue › Saisie vocale Google.
- Traduction locale : touche **Préparer les modèles locaux** une fois en Wi-Fi (~30 Mo par langue). Ensuite elle marche hors ligne.
- ChatGPT : **Compte ChatGPT › Continuer avec ChatGPT**, termine dans le navigateur, reviens à l'app, puis **Autoriser / réparer l'accès**.

## Différences avec la version Mac
- Reconnaissance vocale : service Google/Samsung de la tablette au lieu de Whisper local ; pas de réglage Rapide/Précision, de gain micro ni de témoin micro.
- Traduction locale : ML Kit (qualité inférieure aux modèles OPUS/NLLB, sans adaptation régionale locale). Les adaptations régionales (Guatemala, Montréal/Ontario, Batangas) passent par le Mode GPT.
- Pas de collage automatique dans d'autres applis : Android l'interdit sans service d'accessibilité.
- Pas de conversation « automatique » avec détection de langue : on maintient la touche de la personne qui parle.

## État de vérification
Ce code n'a pas pu être compilé ni essayé sur une tablette dans l'environnement de création (pas de SDK Android). Si la compilation GitHub échoue, envoie le message d'erreur de l'onglet Actions pour correction.
