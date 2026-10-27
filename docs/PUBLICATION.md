# Publier les sources et travailler sur deux ordinateurs

[Présentation](../README.md) · [Avancement](AVANCEMENT.md)

Ce guide accompagne une publication publique du **code en développement**. Il ne déploie aucun service. Chaque étape modifiant Git ou GitHub doit être décidée séparément ; les commandes ci-dessous ne constituent pas un script à lancer en bloc.

## Ce qui appartient au dépôt public

Inclure les sources et tests `src/`, les six migrations qu'il contient, `pom.xml`, les scripts `mvnw` et `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`, `.gitattributes`, `.gitignore`, `README.md` et les trois documents publics de `docs/`.

Les configurations applicatives actuelles sont versionnables parce qu'elles contiennent des adresses locales et des références à des variables, sans valeurs secrètes. Les données d'une base PostgreSQL ne sont pas transférées avec ces sources.

Le `.gitignore` protège notamment les réglages d'éditeur, les fichiers générés, les consignes locales, le suivi privé, les fichiers d'environnement, les clés, journaux et exports de données. Un futur jeu fictif exclu par défaut devra faire l'objet d'une exception précise et d'une revue avant son ajout.

Une exclusion n'efface pas un fichier déjà suivi et peut être contournée par un ajout forcé. Relire les fichiers effectivement sélectionnés avant chaque commit. Ne pas publier de données d'entreprise, coordonnées réelles, empreintes de mots de passe réels ou secrets, même dans un dépôt temporairement privé.

## 1. Initialiser, après accord pour cette seule opération

Se placer dans la racine du dossier serveur, puis :

```sh
git init -b main
```

`init` crée les métadonnées locales de Git et `-b main` nomme la première branche. Cela ne crée rien sur GitHub et n'envoie aucun fichier.

Vérifier ensuite, en lecture seule, `git status --short --untracked-files=all` et `git check-ignore -v -- docs/AUTHENTICATION_PROGRESS.md AGENTS.md .codex/hooks.json`. Ces chemins privés doivent être exclus. Vérifier aussi que `git ls-files` ne les contient pas ; avant tout ajout, cette liste doit être vide.

## 2. Sélectionner le premier contenu, après accord distinct

Préférer une sélection explicite :

```sh
git add -- .gitignore .gitattributes pom.xml mvnw mvnw.cmd .mvn/wrapper/maven-wrapper.properties src README.md docs/AVANCEMENT.md docs/DEVELOPPEMENT.md docs/PUBLICATION.md
```

`add` prépare un instantané des fichiers indiqués pour le prochain commit. Il ne les envoie pas sur Internet.

Examiner ensuite `git diff --cached --name-status` pour les noms et `git diff --cached` pour le contenu. Le suivi privé, les consignes locales, les secrets et `target/` doivent être absents. Vérifier aussi `git ls-files`, car les exclusions seules ne prouvent pas l'absence de fichiers sensibles dans l'index.

## 3. Créer le premier commit, après accord distinct

Vérifier que l'identité Git correspond aux informations que l'on accepte de rendre publiques. Les commits contiennent un nom d'auteur et une adresse e-mail ; choisir au besoin l'adresse de confidentialité proposée par GitHub avant de committer, sans modifier automatiquement l'identité globale.

```sh
git commit -m "Initialisation du chantier Gestion+ serveur"
```

`commit` enregistre localement l'instantané relu. La licence du serveur reste une décision explicite ; ne pas recopier automatiquement celle du client.

## 4. Créer le dépôt GitHub, puis relier et envoyer

Après accord pour créer le dépôt, choisir `gestionplus-server`, une visibilité **publique** et un dépôt vide, sans README ni fichiers générés par GitHub puisque ces fichiers existent localement.

Récupérer son URL réelle. Les exemples utilisent `URL_DU_DEPOT` comme repère à remplacer ; ce n'est pas une URL à exécuter telle quelle.

Après accord pour enregistrer cette adresse :

```sh
git remote add origin URL_DU_DEPOT
```

`origin` est le nom local de l'adresse GitHub. Vérifier l'adresse avec `git remote -v`, puis autoriser séparément l'envoi :

```sh
git push -u origin main
```

`push` envoie les commits. `-u` mémorise la branche distante associée pour les synchronisations suivantes. Sur un dépôt public, le contenu et les métadonnées envoyés deviennent consultables.

Contrôler ensuite le README rendu sur GitHub, les liens relatifs et la liste des fichiers. Le lien du serveur vers le client est déjà documenté. Le lien réciproque dans le README du client pourra être ajouté sur demande distincte ; il n'est pas nécessaire pour publier le serveur et n'ajoute aucune dépendance au démarrage Swing.

## 5. Récupérer et poursuivre sur le PC fixe

Après publication, `git clone URL_DU_DEPOT gestionplus-server` crée une copie avec l'historique dans un nouveau dossier. Placer les deux projets côte à côte, sans imbriquer un dépôt dans l'autre. Choisir un dossier absent ou vide, puis suivre [le guide développeur](DEVELOPPEMENT.md).

Le clonage ne recopie ni les bases, ni les variables d'environnement, ni les fichiers ignorés. Sur un ordinateur déjà équipé du dépôt, commencer par `git status` ; si le travail local est propre, `git pull --ff-only` récupère l'avancée distante sans créer de commit de fusion. En cas de refus ou de changements locaux, examiner la situation avant de poursuivre ; ne pas utiliser une commande de réinitialisation pour l'effacer.

Terminer et envoyer une séance depuis un ordinateur avant de reprendre sur l'autre. Mettre à jour [AVANCEMENT.md](AVANCEMENT.md) lorsque les faits ou les preuves changent, sans y recopier les détails privés.

## Conserver le suivi privé

Le fichier local `docs/AUTHENTICATION_PROGRESS.md` reste intact et ignoré. Les documents publics n'en sont pas une copie intégrale. Les consignes locales et configurations de l'outil restent également exclues.

Pour synchroniser ce suivi et son historique entre les deux postes, utiliser un **dépôt privé distinct**, réservé aux notes et consignes nécessaires, dans un dossier séparé. Y copier le suivi existant seulement après comparaison des versions ; conserver un historique de commits. Ne pas y ajouter de mots de passe, jetons, sauvegardes de bases ou données d'entreprise.

Au changement d'ordinateur : envoyer les notes depuis le premier poste, récupérer leur dernière version sur le second, puis comparer avec l'éventuelle copie locale avant de la mettre à jour. En cas de divergence, conserver les deux versions et fusionner le texte ; éviter deux copies modifiées simultanément sans comparaison. Le dépôt public et le suivi privé restent deux synchronisations distinctes.

La création de ce dépôt privé et les copies ne sont pas réalisées par cette préparation. À défaut, transférer provisoirement le document dans un espace privé avec historique de versions, selon la même règle de comparaison. Le fichier `.git/info/exclude`, s'il est utilisé ultérieurement, est propre à chaque clone ; les exclusions du `.gitignore` livré sont, elles, partagées.

Référence : [règles d'exclusion Git](https://git-scm.com/docs/gitignore).