---
title: "Documentation du serveur Spring Boot"
summary: "Point d’entrée du serveur et liens vers la cible commune Gestion+."
read_when:
  - "commencer une séance sur le serveur ou retrouver un document"
  - "préparer l’intégration Swing Spring Boot PostgreSQL"
---

# Documentation du serveur Spring Boot

[Présentation du serveur](../README.md) · [Vision d’ensemble de Gestion+](https://github.com/lisepinheiro2-lgtm/gestionplus-client/blob/main/docs/vision-ensemble.md) · [Documentation du client](https://github.com/lisepinheiro2-lgtm/gestionplus-client/blob/main/docs/index.md)

Ce serveur fait partie du même projet Gestion+ que le client Swing. Leur réunion à terme est un objectif ; cette organisation documentaire n’effectue aucune fusion de dépôts, de modules ou de code.

## Quoi lire selon la tâche ?

| Besoin | Document de référence |
| --- | --- |
| Comprendre le produit, la couverture des domaines et les frontières client/serveur | [Vision d’ensemble](https://github.com/lisepinheiro2-lgtm/gestionplus-client/blob/main/docs/vision-ensemble.md) |
| Retrouver les décisions structures, droits, archives/facturation, concurrence, hors ligne et documents du 29 octobre | [Synthèse serveur DEC01–DEC06](AVANCEMENT.md#décisions-métier-transversales--29-octobre-2026) et [référence commune](https://github.com/lisepinheiro2-lgtm/gestionplus-client/blob/main/docs/vision-ensemble.md#decisions-metier-2026-10-29) |
| Reprendre le chantier, distinguer implémenté, en cours et prévu | [Avancement et feuille de route](AVANCEMENT.md) |
| Retrouver les sept classes de tests d’invitation et la portée de leur refactorisation | [Découpage du 14 novembre](AVANCEMENT.md#tests-invitations-refactorisation-2026-11-14) |
| Reprendre les tests adaptés du 5 novembre et les problèmes de production laissés ouverts | [Tests écrits, fichiers et limites](AVANCEMENT.md#tests-autorisations-2026-11-05) |
| Comprendre les preuves de tests et les validations manquantes | [Résultats et limites](AVANCEMENT.md#tests--sources-et-résultats-sont-deux-informations-différentes) |
| Explorer les classes, configurations et prérequis locaux | [Guide développeur](DEVELOPPEMENT.md) |
| Refaire les mesures Argon2 et HTTP sur une machine avec des comptes fictifs isolés | [Benchmarks d’authentification](benchmarks/authentication/README.md) |
| Préparer une publication des sources ou une reprise sur un autre ordinateur | [Publication et synchronisation](PUBLICATION.md) |
| Retrouver les besoins fonctionnels par écran et leurs repères | [Feuille de route du client](https://github.com/lisepinheiro2-lgtm/gestionplus-client/blob/main/docs/ROADMAP.md) |

## État et lecture des preuves

Le serveur reste un chantier. [Avancement](AVANCEMENT.md) couvre les états historiques depuis le 27 octobre, les correctifs des 11–12 novembre, les mesures locales du 14 novembre et le découpage des tests d’invitation sur `ad3a59c`. L’utilisatrice confirme les sept classes au vert et 705/705 réussites après ce découpage. Le contrôle des 33 noms de méthodes reste une vérification statique distincte, sans exécution de JUnit pendant l’actualisation documentaire. Les mesures de latence ne démontrent pas l’indistinguabilité complète des rejets. Les parcours HTTP métier et l’intégration Swing restent à construire ou à valider selon les blocs décrits.

La démonstration autonome Swing reste distincte. La sécurité de l’usage connecté doit être imposée côté serveur ; l’absence de connexion n’autorise pas un repli privilégié local.

Ce sommaire, préparé le 28 octobre puis complété les 29 octobre et 5 novembre, est actualisé le **14 novembre 2026** après comparaison des documents et des sources. Aucun test, build, démarrage ou migration exécuté pendant cette actualisation. Les résultats historiques gardent leurs dates et ne valident pas les nouvelles versions des tests.

## Suivi public et suivi privé

Les pages publiques présentent des faits, décisions et limites utiles aux développeurs et recruteurs. Les notes de séance détaillées, informations personnelles, paramètres locaux et journaux bruts n’y sont pas recopiés. Une exclusion Git empêche certains ajouts ordinaires ; elle ne bloque pas la lecture d’un fichier par un outil.

Docmap est facultatif et sert à retrouver les textes. Il n’installe ni base ni service et ne modifie pas les autorisations de l’application.
