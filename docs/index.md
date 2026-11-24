---
title: "Documentation du serveur Spring Boot"
summary: "24 novembre : cycle lié aux sessions dans 908c24d, V34/V35 ; 1 302/1 302 Codex, HTTP de production et Swing encore ouverts."
read_when:
  - "commencer une séance sur le serveur ou retrouver un document"
  - "préparer l’intégration Swing Spring Boot PostgreSQL"
---

# Documentation du serveur Spring Boot

<a id="succession-cycle-2026-11-24"></a>

## Reprise du 24 novembre 2026 — cycle de succession et sessions

**Serveur publié `908c24d`**, après `77ec48e` : le lot Codex de persistance et contrôle des sessions est retrouvé, avec HEAD local/`main` distant concordants. Trois arbres propres avant cette actualisation : serveur `908c24d`, client `48d8f94`, privé `6fc734c`. Le rapport de validation porte sur **`77ec48e` avec modifications**, publiées ensuite dans `908c24d`.

Le service possède désormais le cycle : **preuve de création unique de cinq minutes → offre PENDING annulable de 24 heures → acceptation sans transfert → deux confirmations indépendantes de cinq minutes → finalisation par le successeur depuis sa session confirmée**. La preuve lie compte, version, action, empreinte de session HTTP et récapitulatif ; chaque confirmation lie aussi l’offre et son participant. Une autre session du même successeur ne peut pas finaliser. La preuve est consommée dans la transaction de création ; un rollback la restaure. Renouveler une confirmation ne renouvelle ni l’autre ni l’offre.

**V34** invalide les anciennes preuves, impose leur empreinte de session et adapte les actions ; **mise à niveau coordonnée avec les anciennes instances**, sans compatibilité mixte présumée. **V35** conserve une confirmation par offre/participant ; **V1 à V33 inchangées**. Ces tables métier ne stockent que des empreintes de session : cela ne modifie pas les identifiants internes de Spring Session ou les clés d’activité Redis existantes.

Verrous structures triées → comptes triés → organisation → offre ; relecture SQL de l’organisation sous verrou. Le contrôle conjoint des deux activités utilise Redis TIME à **`authorizedAt`**, sans renouveler l’activité. **Déconnexion avant : refus ; après : le transfert déjà autorisé peut se terminer.** PostgreSQL rend atomiques transfert, clôture COMPLETED et suppression des confirmations ; Redis indisponible au contrôle entraîne un refus. Une panne Redis **après commit** peut empêcher la révocation immédiate alors que le transfert est committé. Le digest du récapitulatif dépend encore du `toString()` Java.

**Codex : 1 302/1 302 serveur**, sans échec, erreur ou test ignoré, en copie isolée avec PostgreSQL/Redis jetables ; **19/19 empreintes conformes**. Finalisation concurrente sur deux JVM, expiration/session différente, course avec annulation, panne Redis et rollback couverts. Le point d’entrée HTTP des deux JVM est un **dispositif de test**, pas une route de production.

**Ouverts : HTTP de production, Swing, audit spécifique, limitation distribuée et notifications du cycle**, distinctes des avis ADMIN existants ; reprise de révocation après commit et digest canonique. Aucun nouveau résultat client, e-mail/SMS réel, réception réelle ou déploiement validé ; aucune validation relancée pendant cette mission documentaire. Les limites MFA antérieures restent ouvertes et les jalons du 23 novembre sont préservés.


Voir [le suivi détaillé](AVANCEMENT.md#succession-cycle-2026-11-24), [les verrous et migrations](DEVELOPPEMENT.md#succession-cycle-2026-11-24) et [la coordination des instances](PUBLICATION.md#succession-cycle-2026-11-24).

**Historique conservé : la reprise du 24 novembre 2026 ci-dessus actualise le cycle ; les jalons du 23 novembre et antérieurs restent datés.**


<a id="notifications-confirmations-succession-2026-11-23"></a>

## Reprise du 23 novembre 2026 — notifications, confirmations et offres

**Serveur `aa9eff7`, présent localement et sur `main` distant.** Six commits publiés ont été vérifiés : `2a1b487`, `b97465f`, `7768258`, `c45d64b`, `468edda` et **`aa9eff7`**, dernier lot de persistance des offres validé sur le socle `468edda` avec modifications locales. Les arbres des trois dépôts étaient propres avant l’actualisation documentaire ; aucun commit local supplémentaire ou changement de code non committé constaté.

- **Notifications V31 :** auteur réel, compte concerné et destinataire distincts ; conservation de la destination historique et auteur inconnu NULL. Avis de désactivation au dernier accès seulement, aux deux participants de chaque transfert ADMIN, y compris pendant une succession, et au titulaire lors de l’activation MFA. Enregistrement dans la transaction métier ; livraison SMTP ultérieure par `recipientUserID`. **Trois tentatives puis abandon audité**, aucune réception/reprise manuelle garantie ni absence de doublons. Mise à niveau des anciennes instances à coordonner pour V31.
- **Confirmations V32 :** empreinte du jeton seule, une preuve par compte/action, cinq minutes au maximum, échéance vérifiée après attente du verrou. Liaison compte/version de sécurité/action/récapitulatif, pas identifiant individuel de session HTTP ; consommation atomique dans la transaction métier et rollback conservant la preuve. OFFER/ACCEPTANCE indépendantes. Vérificateur **bean Spring depuis `468edda`** ; limiteur dédié et audit spécifique des confirmations encore manquants.
- **Offres V33 :** offre de 24 heures créée sans modifier droits/sessions ; transaction métier obligatoire, verrou PostgreSQL d’organisation avant insertion et revalidation du titulaire sortant. Une seule PENDING ou ACCEPTED non expirée par organisation sous READ_COMMITTED ; expiration/annulation permettent une nouvelle offre sans effacer l’historique. Ordre structures triées → comptes triés → organisation → offre, anciennes offres recherchées sans verrou de ligne. **`acceptSuccession()` ignore encore l’offre persistée et ne la marque pas COMPLETED** ; annulation/finalisation non réécrites par ce lot. Préserver l’ordre des verrous lors du raccordement pour éviter l’interblocage.

**Validations antérieures de Codex en copies isolées :** notifications **1 215/1 215** (24 nouveaux cas), confirmations **1 255/1 255** (40), offres **58/58 ciblés puis 1 284/1 284 complets** (29), sans échec, erreur ou test ignoré. La dernière suite conserve les **14 scénarios de sessions à deux JVM** et ajoute **un scénario de concurrence du stockage entre deux JVM**. V33 vérifiée sur bases jetables neuves et V32 → V33 ; V1 à V32 inchangées dans le dernier lot. Résultats successifs à ne pas additionner ou attribuer rétroactivement aux commits suivants.

Aucun test/build/serveur/migration exécuté pendant cette mission documentaire. Aucun e-mail/SMS réel, aucune réception réelle ni déploiement validé ; aucun nouveau résultat client ou parcours HTTP/Swing de succession complet. Les limites MFA restent ouvertes : reprise autonome après contexte perdu/expiré absente et codes non récupérables après réponse perdue ; étape non entièrement close avant traitement ou acceptation explicite.

Voir [les commits et validations](AVANCEMENT.md#notifications-confirmations-succession-2026-11-23), [les contrats et verrous](DEVELOPPEMENT.md#notifications-confirmations-succession-2026-11-23) et [les précautions de publication](PUBLICATION.md#notifications-confirmations-succession-2026-11-23).

**Historique conservé : les jalons antérieurs ci-dessous restent datés ; cette reprise décrit les évolutions du 23 novembre 2026.**


<a id="mfa-swing-2026-11-22"></a>

## Raccordement Swing réalisé, limites de reprise ouvertes — 22 novembre 2026

Le client **`ff68060`** raccorde désormais le parcours MFA restreint : réponse d’activation et preuve éventuelle, dialogue automatique, contexte/cookies/CSRF, clé masquée affichable, confirmation TOTP et présentation unique des dix codes. Transfert de responsabilité de la connexion entre contrôleurs, retour à la connexion normale avec nouveau TOTP, **sans connexion automatique ni accès métier**.

La mention « formulaire Swing à développer/raccorder » ci-dessous est historique. Voir le [suivi client courant](https://github.com/lisepinheiro2-lgtm/gestionplus-client/blob/main/docs/VALIDATION.md#mfa-swing-2026-11-22) pour **443/443 client dans chaque mode**, les quatre scénarios intégrés, les deux écrans en clair/sombre et les douze empreintes recoupées. Serveur de recette **`4a71440`**, identique au HEAD local relu ; la suite serveur complète **n’a pas été relancée**. Ses **1 191/1 191** appartiennent à la validation précédente, pas à ce raccordement.

Recette avec vrai Spring Boot et PostgreSQL/Redis jetables, mais **SMS simulés et HIBP remplacé**, aucun appel Free/OVHcloud ni SMS réel. Environnement arrêté et conteneurs supprimés selon le rapport. Aucun déploiement ou réception réelle validé.

**Ne pas considérer l’étape MFA entièrement close :** après perte/expiration du contexte, le serveur n’offre pas encore une reprise autonome avec une nouvelle preuve vérifiée. L’orientation vers l’administrateur n’ajoute aucun mécanisme administratif de reprise. Si la réponse de confirmation contenant les codes est perdue, le client ne peut pas les relire ; il présente un résultat incertain et ne répète pas automatiquement le TOTP. Ces limites restent à traiter ou à accepter explicitement. Aucun code serveur, test, configuration ou migration modifié par cette mission documentaire ; aucun test, build, serveur ou migration exécuté.

**Jalon antérieur au raccordement MFA Swing du 22 novembre : les dates, résultats et anciennes limites ci-dessous restent historiques ; lire la reprise ci-dessus pour l’état courant.**


<a id="mfa-restreint-2026-11-22"></a>

## Reprise MFA restreint — 22 novembre 2026

**Serveur relu `51407e3`.** Contexte `EnrollmentPrincipal` de dix minutes absolues, sans accès métier ; preuve consommée une seule fois à l’ouverture, begin réutilisable sans renouveler le secret ni l’échéance, confirmation puis fermeture du contexte. Connexion normale avec un nouveau TOTP ensuite, sans promotion automatique.

- [Contrat HTTP, sérialisation Redis, révocations et quotas](AVANCEMENT.md#mfa-restreint-2026-11-22).
- [Chaîne de lecture, erreurs réelles et migrations V29/V30](DEVELOPPEMENT.md#mfa-restreint-2026-11-22).
- [Publication et précautions d’exploitation](PUBLICATION.md#mfa-restreint-2026-11-22).

**Codex : 1 191/1 191 serveur, dont 14 scénarios sur deux JVM**, sans échec, erreur ou test ignoré. Les 25 empreintes du rapport correspondent à l’état actuel ; le rapport référence `e5f3434` avant modifications. Les 1 157/1 157 précédents restent historiques. Aucun test relancé pendant cette mise à jour.

Le formulaire Swing reste à raccorder, sans nouveau code ou résultat client. Réception réelle de SMS et déploiement non validés. Refus CSRF : **403 `FORBIDDEN`**, avec `CSRF_REJECTED` comme raison d’audit.

**Historique antérieur à la reprise MFA du 22 novembre : les résultats, décisions et anciennes limites ci-dessous gardent leur date et leur périmètre.**


<a id="activation-sms-swing-2026-11-21"></a>

## Reprise activation SMS/Swing — 21 novembre 2026

**Serveur `165d2e0`, client `ec08c42`.** Défi revalidé sous verrou, téléphone/instant/jeton courants, garde contre transaction englobante, envoi après commit et échec initial conservé en cas de compensation défaillante. V28 ajoute confirmation et récupération durables ; adaptateur OVHcloud EU présent, sans envoi réel pendant les validations.

- [Code, V28, reprise multi-instance et suites complètes](AVANCEMENT.md#activation-sms-swing-2026-11-21).
- [Configuration externe OVHcloud et prérequis Testcontainers](DEVELOPPEMENT.md#activation-sms-swing-2026-11-21).
- [Préparer la publication et l’exploitation sans secrets](PUBLICATION.md#activation-sms-swing-2026-11-21).

Codex : **1 143/1 143 serveur**, dont 12 scénarios à deux JVM ; **413/413 client sur classpath et en module**. Les fichiers inventoriés correspondent aux preuves privées, sans réexécution pendant cette mission. Le filtre public de Lise est désormais raccordé : les anciennes notes de squelette restent historiques.

La réception OVHcloud réelle, le premier enrôlement TOTP et le déploiement restent ouverts. Les tests isolés ne sont pas une recette complète Swing → OVHcloud → téléphone.

**Historique antérieur à cette reprise activation SMS/Swing : les dates, preuves et anciennes limites ci-dessous gardent leur contexte ; la reprise ci-dessus décrit l’état courant.**


<a id="multi-instance-2026-11-21"></a>

## Reprise multi-instance — 21 novembre 2026

**Serveur `f4c6931`** : sessions Spring Session Data Redis, registre/révocations et activité partagés, JSON à types autorisés, limiteurs atomiques Redis, cohérence des clés et profil proxy explicite. La conception multi-instance remplace l’ancienne décision d’instance unique.

- [Code, attribution et preuves : 291/291 tests ciblés, dont 10 scénarios sur deux JVM](AVANCEMENT.md#multi-instance-2026-11-21).
- [Paramètres externes Redis, TLS, clés communes, proxy et audit par instance](DEVELOPPEMENT.md#multi-instance-2026-11-21).
- [Publication et préparation de l’exploitation](PUBLICATION.md#multi-instance-2026-11-21).
- [Benchmark HTTP adapté, compilé sans nouvelle mesure](benchmarks/authentication/README.md#multi-instance-2026-11-21).

`RequestRateLimiter` et son adaptateur Redis existent ; le filtre public est absent du commit ; un squelette local de Lise est apparu depuis, sans raccordement HTTP au contrôle. Redis partagé ne valide ni Redis Cluster ni une haute disponibilité complète. Suite complète non exécutée ; anciens résultats globaux conservés avec leurs versions. Aucun test ni lancement pendant cette mise à jour.


**Jalon antérieur au lot multi-instance du 21 novembre : lire la reprise ci-dessus pour l’état courant et les décisions remplacées.**

<a id="reprise-2026-11-21"></a>

## Reprise du 21 novembre 2026

Serveur relu `72aa7f4` : liste/détail des structures autorisées (`1ce0561`, `63046e8`) et correction des compteurs HIBP. Services de gouvernance existants distingués des parcours HTTP/Swing à raccorder.

- [Routes, code vérifié, validations et 13 tâches ouvertes du gist](AVANCEMENT.md#reprise-2026-11-21).
- [Repères d’investigation et limites des vérifications](DEVELOPPEMENT.md#reprise-2026-11-21).

**924/924 serveur sur `63046e8`** rapportés par Lise avant correction ; **19/19 HIBP** rapportés pour Codex ensuite, client HTTP simulé. « Tout est vert » confirmé ultérieurement par Lise ne fournit aucun nouveau total. Aucun test ni lancement pendant cette mise à jour ; les anciens résultats restent datés.


<a id="reprise-swing-2026-11-20"></a>

## Reprise du raccordement — 20 novembre 2026

**Historique du 20 novembre ; lire la reprise du 21 novembre ci-dessus pour l’état courant.**

TOTP, identité, accueil et déconnexion/reconnexion Swing sont validés manuellement par Lise sur un environnement isolé. Les **284/284** concernent le client `03387cc` ; les **900/900** restent la validation serveur de `4c630ee` du 19 novembre.

- [Recette réelle, auteurs des contrôles et limites](AVANCEMENT.md#connexion-swing-2026-11-20).
- [Préparer un essai isolé sans publier de secret](DEVELOPPEMENT.md#essai-client-isole-2026-11-20).
- [Suite client : métier connecté et expiration](https://github.com/lisepinheiro2-lgtm/gestionplus-client/blob/main/docs/ROADMAP.md#suite-connexion-2026-11-20).

Les états historiques ci-dessous restent datés ; aucune suite n'est relancée par cette actualisation.

[Présentation du serveur](../README.md) · [Vision d’ensemble de Gestion+](https://github.com/lisepinheiro2-lgtm/gestionplus-client/blob/main/docs/vision-ensemble.md) · [Documentation du client](https://github.com/lisepinheiro2-lgtm/gestionplus-client/blob/main/docs/index.md)

Ce serveur fait partie du même projet Gestion+ que le client Swing. Leur réunion à terme est un objectif ; cette organisation documentaire n’effectue aucune fusion de dépôts, de modules ou de code.

## Quoi lire selon la tâche ?

| Besoin | Document de référence |
| --- | --- |
| Comprendre le produit, la couverture des domaines et les frontières client/serveur | [Vision d’ensemble](https://github.com/lisepinheiro2-lgtm/gestionplus-client/blob/main/docs/vision-ensemble.md) |
| Retrouver les décisions structures, droits, archives/facturation, concurrence, hors ligne et documents du 29 octobre | [Synthèse serveur DEC01–DEC06](AVANCEMENT.md#décisions-métier-transversales--29-octobre-2026) et [référence commune](https://github.com/lisepinheiro2-lgtm/gestionplus-client/blob/main/docs/vision-ensemble.md#decisions-metier-2026-10-29) |
| Dernier état : 900/900 rapportés sur `4c630ee`, sécurité HTTP, sessions et SMS fictif | [Jalon du 19 novembre](AVANCEMENT.md#securite-http-sessions-2026-11-19) |
| Reprendre le chantier, distinguer implémenté, en cours et prévu | [Avancement et feuille de route](AVANCEMENT.md) |
| Création des défis SMS, tests et limites du 17 novembre | [Service, quota et assemblage à compléter](AVANCEMENT.md#creation-defis-sms-2026-11-17) |
| Demande de code SMS | [Jeton d’invitation, audit et tests du service](AVANCEMENT.md#demande-code-sms-2026-11-17) · [Contrôleur et accès HTTP](AVANCEMENT.md#route-demande-code-sms-2026-11-18) |
| Vérificateur HMAC et tests du 18 novembre | [Code concret et niveau de preuve](AVANCEMENT.md#verification-hmac-sms-2026-11-18) · [Réserves : audits, temps et compensation](AVANCEMENT.md#reserves-sms-2026-11-18) |
| Reprendre le stockage des défis SMS, V27 et le bilan de 723/723 | [Stockage d’activation du 15 novembre](AVANCEMENT.md#defis-sms-activation-stockage-2026-11-15) |
| Reprendre les refactorings de l’activation et les tests corrigés | [Service séparé, défi validé et consommation transactionnelle](AVANCEMENT.md#activation-defis-sms-refactorisation-2026-11-15) |
| Retrouver les sept classes de tests d’invitation et la portée de leur refactorisation | [Découpage du 14 novembre](AVANCEMENT.md#tests-invitations-refactorisation-2026-11-14) |
| Reprendre les tests adaptés du 5 novembre et les problèmes de production laissés ouverts | [Tests écrits, fichiers et limites](AVANCEMENT.md#tests-autorisations-2026-11-05) |
| Comprendre les preuves de tests et les validations manquantes | [Résultats et limites](AVANCEMENT.md#tests--sources-et-résultats-sont-deux-informations-différentes) |
| Explorer les classes, configurations et prérequis locaux | [Guide développeur](DEVELOPPEMENT.md) |
| Refaire les mesures Argon2 et HTTP sur une machine avec des comptes fictifs isolés | [Benchmarks d’authentification](benchmarks/authentication/README.md) |
| Préparer une publication des sources ou une reprise sur un autre ordinateur | [Publication et synchronisation](PUBLICATION.md) |
| Retrouver les besoins fonctionnels par écran et leurs repères | [Feuille de route du client](https://github.com/lisepinheiro2-lgtm/gestionplus-client/blob/main/docs/ROADMAP.md) |

## État et lecture des preuves

Au 19 novembre 2026, `4c630ee` complète les tests avec la vraie chaîne Spring Security et les cycles de session. **900/900 tests réussis dans Eclipse sont confirmés par Lise sur cet état**, sans exécution de l’assistant. [Validation et limites](AVANCEMENT.md#securite-http-sessions-2026-11-19)

Le POST SMS est autorisé sans connexion avec CSRF et jeton d’invitation. Le simulateur conditionnel est présent depuis `4008538` et le chemin partagé depuis `a1edc64`. L’expéditeur minimal précède le raccordement serveur–client ; le fournisseur SMS réel et le parcours d’activation SMS dans Swing restent à intégrer ; la connexion et les sessions Swing sont désormais raccordées. Le contrat d’appel hors transaction et les réserves de l’invitation et du téléphone restent applicables. [Composants SMS](AVANCEMENT.md#beans-definitifs-apres-raccordement)

La démonstration autonome Swing reste distincte. La sécurité de l’usage connecté doit être imposée côté serveur ; l’absence de connexion n’autorise pas un repli privilégié local.

Sommaire actualisé le **20 novembre 2026** ; les preuves du 19 novembre gardent leur portée.

## Suivi public et suivi privé

Les pages publiques présentent des faits, décisions et limites utiles aux développeurs et recruteurs. Les notes de séance détaillées, informations personnelles, paramètres locaux et journaux bruts n’y sont pas recopiés. Une exclusion Git empêche certains ajouts ordinaires ; elle ne bloque pas la lecture d’un fichier par un outil.

Docmap est facultatif et sert à retrouver les textes. Il n’installe ni base ni service et ne modifie pas les autorisations de l’application.
