---
summary: "24 novembre : lot publié 908c24d et coordination V34 ; limites de révocation Redis après commit et raccordements ouverts."
read_when:
  - "préparer une revue avant commit ou publication GitHub"
  - "retrouver les limites du transfert Git et de la synchronisation des notes privées"
---

# Publier les sources et travailler sur deux ordinateurs

<a id="succession-cycle-2026-11-24"></a>

## Publication du cycle et coordination V34 — 24 novembre 2026

Deux commits du 24 novembre sont présents sur GitHub : **`77ec48e`** (15:50:43 Paris) puis **`908c24d`** (16:12:15 Paris). Le dernier reprend le lot Codex validé sur le socle `77ec48e` avec modifications locales. Le HEAD du checkout et `main` distant correspondent à `908c24d`. Les traces locales de push sont 15:50:49 et 16:12:19 (UTC+01) ; ce sont des entrées « update by push » du reflog local, pas des heures d’audit GitHub. Voir [le cycle, l’inventaire et les validations](AVANCEMENT.md#succession-cycle-2026-11-24).

Arbres propres avant la mission : serveur `908c24d`, client `48d8f94` et privé `6fc734c`. Aucun changement de code encore local ; les ajouts de cette mission sont uniquement documentaires et non committés. Les publications client/privées ne sont pas déduites de leur seul historique local.

**Préparer une mise à niveau coordonnée pour V34 :** elle supprime les anciennes preuves incompatibles de `privileged_confirmation_proofs`, ajoute `session_id_hash` obligatoire et remplace les actions de succession antérieures par `SUPER_ADMIN_SUCCESSION_CREATE` (transfert ADMIN conservé). Les anciennes instances qui utilisent le schéma/actions précédents ne doivent pas être supposées compatibles ; coordonner leur arrêt/basculement et réémettre les preuves. Aucun basculement réel ou déploiement mixte validé.

**V35** ajoute les confirmations par offre/participant, liées au compte/version, à l’empreinte de sa session et au récapitulatif, cinq minutes maximum. **V1 à V33 inchangées** par ces deux commits. Ces stockages métier ne conservent que des empreintes de session ; Spring Session et les clés d’activité Redis existantes ne sont pas rendus anonymes ou globalement hachés par cette évolution.

Le cycle métier est présent jusqu’à COMPLETED : création avec preuve unique de cinq minutes, offre annulable de 24 heures, acceptation sans transfert, puis deux confirmations indépendantes de cinq minutes ; finalisation réservée au successeur depuis sa session confirmée. Verrous structures triées → comptes triés → organisation → offre, relecture SQL de l’organisation sous verrou ; préserver l’ordre lors des évolutions.

Le contrôle conjoint Redis à `authorizedAt` fixe la règle : logout avant, refus ; logout après, finalisation déjà autorisée possible. Transfert/clôture/suppression des confirmations sont atomiques dans PostgreSQL. **La révocation Redis après commit n’est pas atomique avec PostgreSQL** : en cas de panne, le transfert peut être committé malgré une erreur et sans révocation immédiate garantie. Prévoir une reprise ; ne pas annoncer de rollback après commit. Le digest du récapitulatif repose encore sur `toString()` Java et devra être stabilisé.

Codex : **1 302/1 302 tests serveur**, zéro échec/erreur/ignoré, copie isolée PostgreSQL/Redis jetables, 19 empreintes conformes. Le test concurrent à deux JVM utilise un point d’entrée de **`src/test/.../IsolatedClusterApplication`**, pas une route HTTP de production. Aucune validation relancée pendant cette mission ; aucun nouveau résultat client, envoi réel d’e-mail/SMS ou déploiement validé.

**À poursuivre :** HTTP de production et Swing ; audit spécifique des nouvelles opérations/refus, limitation distribuée et notifications du cycle. Les avis de transferts ADMIN, la limite SMTP de trois tentatives puis abandon audité et les limites MFA de reprise restent ceux des jalons antérieurs. Les documents et preuves du 23 novembre sont conservés.

**Historique conservé : la reprise du 24 novembre 2026 ci-dessus actualise le cycle ; les jalons du 23 novembre et antérieurs restent datés.**


<a id="notifications-confirmations-succession-2026-11-23"></a>

## Publications constatées et limites de mise à niveau — 23 novembre 2026

Le serveur **`aa9eff7`** est présent sur `main` distant et dans le checkout local. Six commits publiés le 23 novembre, heure de Paris, ont été vérifiés : `2a1b487`, `b97465f`, `7768258`, `c45d64b`, `468edda`, `aa9eff7`. Le dernier est la persistance PostgreSQL des offres avec V33 et tests, validée sur le socle `468edda` avec modifications locales. Voir [l’inventaire horodaté et les validations](AVANCEMENT.md#notifications-confirmations-succession-2026-11-23). Les heures de push de cet inventaire proviennent du reflog local « update by push », pas d’un audit GitHub ; la présence distante et le HEAD ont été contrôlés séparément.

Avant cette mission : arbres propres, serveur `aa9eff7`, client `9c9e1b9` (documentation ; code MFA `ff68060`), dépôt privé `8bfed9e`. Aucun nouveau commit du 23 novembre dans les historiques locaux client/privé. Les modifications produites par cette mission sont **documentaires et non committées** ; aucune commande Git modifiante exécutée.

Les validations **Codex** sont attachées à trois copies isolées successives : **1 215/1 215** notifications, **1 255/1 255** confirmations, puis **58/58 ciblés et 1 284/1 284 complets** offres. Ne pas les additionner ou les attribuer à Lise. Ni tests relancés ici, ni nouveau résultat client ; aucun parcours HTTP/Swing complet de succession validé.

**Précautions à préparer avant un déploiement ultérieur :**

- V31 conserve les destinataires historiques en renommant `actor_id` en `recipient_user_id` et ajoute l’auteur réel nullable. Coordonner migration et basculement des instances : les anciens noms de colonnes/types ne permettent pas de supposer la compatibilité d’un déploiement mixte. Aucun basculement réel testé.
- V32 stocke une empreinte de preuve par compte/action, cinq minutes maximum, consommée dans la transaction métier ; liaison à la version du compte, pas à une session HTTP individuelle. `StoredPrivilegedConfirmationVerifier` est un bean depuis `468edda` ; limiter les tentatives et ajouter l’audit spécifique restent ouverts.
- V33 utilise le verrou PostgreSQL partagé d’organisation sous READ_COMMITTED, non un index unique temporel. Conserver structures triées → comptes triés → organisation → offre ; une inversion future expose à un interblocage. Les anciennes offres sont recherchées sans verrou de ligne à la création.
- V33 a été vérifiée en base jetable neuve et en passage V32 → V33, V1 à V32 inchangées dans ce lot. Ce résultat ne vaut ni répétition générale ni validation de production.
- La livraison SMTP reste plafonnée à **trois tentatives puis abandon audité**. Ni réception garantie, ni reprise manuelle garantie, ni absence de doublons ; aucune livraison réelle d’e-mail/SMS ou mise en production validée.

**Restent à raccorder :** cycle métier des offres, notamment `acceptSuccession()` qui ne consulte pas l’offre et ne la termine pas en COMPLETED ; parcours d’annulation/finalisation non réécrits dans le dernier lot ; exposition HTTP/Swing complète. Les limites MFA de contexte perdu/expiré et de réponse contenant les codes perdue restent à traiter ou à accepter explicitement, sans mécanisme administratif supplémentaire livré.

**Historique conservé : les jalons antérieurs ci-dessous restent datés ; cette reprise décrit les évolutions du 23 novembre 2026.**


<a id="mfa-swing-2026-11-22"></a>

## Raccordement Swing réalisé, limites de reprise ouvertes — 22 novembre 2026

Le client **`ff68060`** raccorde désormais le parcours MFA restreint : réponse d’activation et preuve éventuelle, dialogue automatique, contexte/cookies/CSRF, clé masquée affichable, confirmation TOTP et présentation unique des dix codes. Transfert de responsabilité de la connexion entre contrôleurs, retour à la connexion normale avec nouveau TOTP, **sans connexion automatique ni accès métier**.

La mention « formulaire Swing à développer/raccorder » ci-dessous est historique. Voir le [suivi client courant](https://github.com/lisepinheiro2-lgtm/gestionplus-client/blob/main/docs/VALIDATION.md#mfa-swing-2026-11-22) pour **443/443 client dans chaque mode**, les quatre scénarios intégrés, les deux écrans en clair/sombre et les douze empreintes recoupées. Serveur de recette **`4a71440`**, identique au HEAD local relu ; la suite serveur complète **n’a pas été relancée**. Ses **1 191/1 191** appartiennent à la validation précédente, pas à ce raccordement.

Recette avec vrai Spring Boot et PostgreSQL/Redis jetables, mais **SMS simulés et HIBP remplacé**, aucun appel Free/OVHcloud ni SMS réel. Environnement arrêté et conteneurs supprimés selon le rapport. Aucun déploiement ou réception réelle validé.

**Ne pas considérer l’étape MFA entièrement close :** après perte/expiration du contexte, le serveur n’offre pas encore une reprise autonome avec une nouvelle preuve vérifiée. L’orientation vers l’administrateur n’ajoute aucun mécanisme administratif de reprise. Si la réponse de confirmation contenant les codes est perdue, le client ne peut pas les relire ; il présente un résultat incertain et ne répète pas automatiquement le TOTP. Ces limites restent à traiter ou à accepter explicitement. Aucun code serveur, test, configuration ou migration modifié par cette mission documentaire ; aucun test, build, serveur ou migration exécuté.

**Jalon antérieur au raccordement MFA Swing du 22 novembre : les dates, résultats et anciennes limites ci-dessous restent historiques ; lire la reprise ci-dessus pour l’état courant.**


<a id="mfa-restreint-2026-11-22"></a>

## Publication du parcours MFA restreint — 22 novembre 2026

Le commit local **`51407e3`** est relu ; sa publication distante n’est pas déduite de l’état local. Le rapport de Codex référence `e5f3434` avant modifications ; ses 25 empreintes correspondent au code courant. **1 191/1 191 serveur**, dont 14 scénarios à deux JVM, sans nouvelle validation du client ou d’un déploiement réel.

**V29** conserve les preuves MFA, **V30** ajoute un index unique sur `proof_hash`. Ces fichiers sont présents et ont été validés dans des PostgreSQL jetables ; aucune migration existante n’est modifiée et aucune migration n’est exécutée pendant cette actualisation. Leur application en exploitation reste une opération distincte à préparer et autoriser.

Conserver hors publication preuves brutes, secrets TOTP, clés de configuration, codes de secours, cookies, journaux et données de validation. Le contexte Redis restreint ne contient que l’identité, la version de sécurité et l’échéance ; la réponse begin contenant une clé TOTP n’est pas un contenu à journaliser.

Dix minutes absolues et cinq soumissions de confirmation par compte/dix minutes sont contrôlées côté serveur avec Redis partagé ; le compteur ne dépend pas du rollback PostgreSQL. Cela ne valide ni Redis Cluster/haute disponibilité, ni proxy/réseau/TLS réels. Le formulaire Swing reste à raccorder. Aucun SMS réel ni appel à Free/OVHcloud, aucun déploiement validé ; aucun commit, push ou pull effectué ici. Voir [contrat et exploitation](DEVELOPPEMENT.md#mfa-restreint-2026-11-22).

**Historique antérieur à la reprise MFA du 22 novembre : les résultats, décisions et anciennes limites ci-dessous gardent leur date et leur périmètre.**


<a id="activation-sms-swing-2026-11-21"></a>

## Publication du lot activation SMS/Swing — 21 novembre 2026

Les commits locaux `165d2e0` serveur et `ec08c42` client sont relus, sans déduire leur publication distante. Les suites complètes antérieures de Codex (1 143/1 143 serveur et 413/413 client dans chaque mode) sont recoupées avec les 23/11 fichiers inventoriés dans les preuves ; aucune modification ultérieure n’en hérite automatiquement.

**V28 ajoute confirmation/reprise des SMS et révoque les anciens défis encore ouverts**, en conservant leur historique. Préparer ce changement et sa conséquence sur les codes déjà envoyés avant tout déploiement autorisé. Aucune migration n’est lancée ici.

OVHcloud exige une sélection explicite et des paramètres externes ; ne publier ni clés, jetons, liens d’invitation réels, numéros, captures sensibles ou journaux privés. Free reste pour les essais locaux ; ne pas activer plusieurs expéditeurs. Voir [configuration et reprise](DEVELOPPEMENT.md#activation-sms-swing-2026-11-21).

Aucun compte fournisseur réel, SMS facturé ou déploiement n’a été utilisé pour la validation. Acceptation fournisseur et réception sur téléphone restent distinctes ; TLS/proxy/réseau, clés, audit par instance, sauvegarde/restauration et exploitation gardent leurs validations propres. Premier enrôlement TOTP toujours séparé. Aucun commit, push, pull ou déploiement effectué par cette actualisation.

**Historique antérieur à cette reprise activation SMS/Swing : les dates, preuves et anciennes limites ci-dessous gardent leur contexte ; la reprise ci-dessus décrit l’état courant.**


<a id="multi-instance-2026-11-21"></a>

## Préparer la publication multi-instance — 21 novembre 2026

Sur `f4c6931`, les sessions et protections de connexion partagées reposent sur Redis. Publier les sources n’installe ni Redis, ni reverse proxy, ni réseau de production. Les **291/291 tests ciblés** de Codex dans une copie isolée, dont dix scénarios à deux JVM avec Redis/PostgreSQL jetables, ne constituent pas une suite complète ou une recette d’infrastructure de production.

Préparer hors dépôt les paramètres Redis, un espace de noms commun par environnement, les clés communes et leur rotation coordonnée, TLS et les proxies de confiance explicites. Prévoir un répertoire d’audit chiffré durable **distinct pour chaque instance**, avec reprise/collecte et sauvegarde. Voir les [paramètres et limites](DEVELOPPEMENT.md#multi-instance-2026-11-21) ; aucune valeur secrète n’est fournie par la documentation.

Conserver hors dépôt preuves privées, paramètres locaux, mots de passe, clés, comptes réels et journaux. Le profil `prod` exige TLS Redis et conserve HTTPS/cookie Secure ; le réseau, les ACL, certificats, notifications Redis et restaurations restent à configurer et à valider. Le contrôle de cohérence des clés ne constitue pas un dispositif de rotation ou de haute disponibilité.

L’ancienne décision d’instance unique est remplacée par la conception multi-instance. Redis partagé ne signifie pas Redis Cluster validé. Files d’envoi, reprises et quotas globaux exigent encore leur propre vérification en exploitation. Commits, pushs et déploiements restent à la main de Lise ; aucune de ces opérations n’est effectuée par cette actualisation.


<a id="confidentialite-essai-local-2026-11-20"></a>

## Données d'essai et publication — 20 novembre 2026

Le parcours connecté réel est décrit dans l'[avancement](AVANCEMENT.md#connexion-swing-2026-11-20). Outils locaux, fichiers Eclipse, paramètres PostgreSQL, identifiants, facteurs TOTP, clés et codes de récupération restent hors dépôt. Les documents publics présentent le comportement et les preuves sans recopier ces fichiers privés.

Le SUPER_ADMIN fictif de la base isolée n'est pas un compte par défaut de l'application publiée. Les opérations de commit et de push restent sous le contrôle de Lise.

[Présentation](../README.md) · [Avancement](AVANCEMENT.md) · [Sommaire](index.md)

## Situation à la reprise — 28 octobre 2026

Les dépôts client et serveur existent déjà. Les étapes d’initialisation et de premier envoi ci-dessous restent des repères pour un nouveau dépôt ; elles ne sont pas à réexécuter pour ajouter Docmap ou reprendre le travail.

L’utilisatrice garde les opérations Git dans GitHub Desktop ou son terminal, y compris pour la documentation. L’assistant peut préparer les textes autorisés et expliquer les différences ; il ne lance ni sélection de fichiers, ni commit, ni push. Les commandes de ce guide sont des explications, pas des autorisations.

Le [sommaire serveur](index.md) et la [vision commune](https://github.com/lisepinheiro2-lgtm/gestionplus-client/blob/main/docs/vision-ensemble.md) organisent le suivi des deux composants. La réunion technique future reste à décider ; elle ne modifie pas le démarrage autonome Swing. Les nouveaux liens entre dépôts ne seront consultables sur GitHub qu’après la publication manuelle des documents correspondants.

Ce guide accompagne une publication publique du **code en développement**. Il ne déploie aucun service. Chaque étape modifiant Git ou GitHub doit être décidée séparément ; les commandes ci-dessous ne constituent pas un script à lancer en bloc.

## Ce qui appartient au dépôt public

Inclure les sources et tests `src/`, y compris les migrations de `src/main/resources/db/migration/`, `pom.xml`, les scripts `mvnw` et `mvnw.cmd`, `.mvn/wrapper/maven-wrapper.properties`, `.gitattributes`, `.gitignore`, `README.md` et les documents publics de `docs/` : `index.md`, `AVANCEMENT.md`, `DEVELOPPEMENT.md` et `PUBLICATION.md`.

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
git add -- .gitignore .gitattributes pom.xml mvnw mvnw.cmd .mvn/wrapper/maven-wrapper.properties src README.md docs/index.md docs/AVANCEMENT.md docs/DEVELOPPEMENT.md docs/PUBLICATION.md
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

Contrôler ensuite le README rendu sur GitHub, les liens relatifs et la liste des fichiers. Le lien du serveur vers le client est déjà documenté. Le README du client renvoie également vers le serveur et la vision commune. Ces liens documentaires n’ajoutent aucune dépendance au démarrage Swing.

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