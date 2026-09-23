# NokoPrint — Impression USB directe

Application Android qui imprime directement via un câble USB OTG, sans
passer par un PC ni par le cloud, en s'appuyant sur des standards USB
ouverts.

## Ce que fait réellement l'application

1. **Détection** : recherche tout périphérique USB déclarant la classe
   d'interface standard "Printer" (classe 7), qu'importe la marque.
2. **Identification** : lit la chaîne d'identité IEEE 1284 de l'imprimante
   (requête `GET_DEVICE_ID`), qui contient un champ `CMD:` listant les
   langages d'impression que l'imprimante comprend réellement
   (ex: `CMD:PCL,PJL;` ou `CMD:ESC/POS;`).
3. **Conversion** : transforme l'image choisie en :
   - **PCL5** (raster graphics) si l'imprimante déclare le supporter —
     couvre une grande partie des imprimantes de bureau (HP, Brother...).
   - **ESC/POS** sinon, par défaut — couvre les imprimantes de tickets et
     reçus thermiques (Epson, Star, et la plupart des clones chinois).
4. **Envoi** : transmission des octets bruts par transfert USB "bulk", sans
   dépendre d'un pilote installé sur le téléphone.

## Ce que l'application NE fait PAS (à savoir avant de tester)

Les imprimantes qui n'acceptent **que** leur propre langage propriétaire
non documenté (Canon CAPT/UFRII LT, certains modèles Samsung/Xerox anciens)
**ne fonctionneront pas** avec cette version : leur `Device ID` ne déclare
généralement ni PCL ni PostScript. C'est exactement le cas de nombreuses
imprimantes laser Canon d'entrée de gamme, dont la LBP6030.

Ajouter le support d'un modèle propriétaire précis est possible, mais
nécessite pour ce modèle une analyse de capture USB dédiée (voir les
échanges précédents sur la capture Wireshark/USBPcap) — ce n'est pas
quelque chose que l'app peut deviner automatiquement.

## Tester avec votre imprimante

1. Compilez et installez l'app (Android Studio, ou APK direct).
2. Branchez le téléphone à l'imprimante via un câble USB OTG.
3. Appuyez sur "Détecter l'imprimante USB" — l'app affichera la chaîne de
   langages détectés le cas échéant. Ce texte à lui seul est une
   information précieuse : il dit exactement ce que l'imprimante sait
   faire.
4. Choisissez une image, laissez "تلقائي" (Auto) ou forcez PCL/ESC-POS
   manuellement, puis imprimez.

## Obtenir le fichier .apk

### Option A — Sans rien installer sur votre ordinateur

Ce projet contient un fichier `.github/workflows/android.yml` qui compile
l'APK automatiquement dans le cloud, gratuitement, via GitHub Actions :

1. Créer un compte gratuit sur [github.com](https://github.com).
2. Créer un nouveau dépôt ("New repository"), lui donner un nom (ex.
   `nokoprint`).
3. Envoyer le contenu du dossier `NokoPrint` dans ce dépôt — le plus
   simple : bouton "Add file" → "Upload files" sur la page du dépôt, puis
   glisser-déposer tout le contenu du dossier.
4. Aller dans l'onglet **Actions** du dépôt : une exécution "Build APK"
   démarre automatiquement (quelques minutes).
5. Une fois la coche verte ✅ affichée, cliquer sur l'exécution puis
   télécharger l'artefact **NokoPrint-debug-apk** en bas de la page —
   c'est un zip contenant `app-debug.apk`.
6. Transférer ce fichier sur le téléphone et l'installer (autoriser
   "sources inconnues" si demandé).

### Option B — Avec Android Studio

Ouvrir le dossier dans Android Studio (Giraffe ou plus récent), laisser
Gradle synchroniser, puis Build > Build APK(s).

## Prochaines étapes possibles

- Ajout d'un rendu PostScript (nécessite une bibliothèque de rendu, plus
  lourde) pour couvrir davantage d'imprimantes de bureau/professionnelles.
- Prise en charge de fichiers PDF multi-pages (rendu page par page via
  `android.graphics.pdf.PdfRenderer`, déjà disponible nativement sur
  Android — actuellement l'app n'accepte que des images).
- Ajout ciblé du protocole Canon UFRII LT pour la LBP6030 précisément, si
  une capture USB réelle est fournie pour analyse.
