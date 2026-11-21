---
title: "Documentation du serveur Spring Boot"
summary: "Reprise f4c6931 : multi-instance Redis, 291 tests ciblés et limites de production."
read_when:
  - "commencer une séance sur le serveur ou retrouver un document"
  - "préparer l’intégration Swing Spring Boot PostgreSQL"
---

# Documentation du serveur Spring Boot

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
