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
| Création des défis SMS, tests et limites du 17 novembre | [Service, quota et assemblage à compléter](AVANCEMENT.md#creation-defis-sms-2026-11-17) |
| Demande de code SMS et résultat de 838/838 | [Jeton d’invitation, audit et tests corrigés](AVANCEMENT.md#demande-code-sms-2026-11-17) |
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

Au 17 novembre 2026, le `HEAD` local relu est `6c374a1`. `requestActivationChallenge(invitationID, token)` vérifie le jeton d’invitation avant de déléguer la création du défi ; les refus et échecs sont audités avec `INVITATION_SMS_REQUEST`. Deux classes locales ajoutent 10 méthodes unitaires, dont des méthodes paramétrées, et six scénarios PostgreSQL. Lise rapporte **838/838 tests réussis après correction des jetons de test** sur cet état local complété, encore non commité pour les deux nouvelles classes ; voir la [validation détaillée](AVANCEMENT.md#demande-code-sms-2026-11-17). L’assistant ne les exécute pas. Les 788/788 sur `5a5d26f`, les 745/745 du 16 novembre et les 723/723 du 15 novembre restent historiques.

La [fenêtre du téléphone lu avant verrou](AVANCEMENT.md#telephone-avant-verrou-defis-sms), l’invitation non relue sous ce verrou et le [contrat d’appel hors transaction](AVANCEMENT.md#contrat-appel-creation-defis-sms), sans garde automatique, restent suivis. Les [beans SMS définitifs et les autres composants applicatifs provisoires](AVANCEMENT.md#beans-definitifs-apres-raccordement) sont à reprendre après raccordement serveur–client. Les mocks restent isolés sous `src/test` ; aucun parcours SMS réel, nouvel accès HTTP ou raccordement Swing n’est validé par ce résultat.

La démonstration autonome Swing reste distincte. La sécurité de l’usage connecté doit être imposée côté serveur ; l’absence de connexion n’autorise pas un repli privilégié local.

Ce sommaire est actualisé le **17 novembre 2026** après lecture des sources et comparaison documentaire. Le résultat de 838/838 est rapporté par Lise pour l’état local complétant `6c374a1` ; aucun test, build, démarrage ou migration exécuté par l’assistant.

## Suivi public et suivi privé

Les pages publiques présentent des faits, décisions et limites utiles aux développeurs et recruteurs. Les notes de séance détaillées, informations personnelles, paramètres locaux et journaux bruts n’y sont pas recopiés. Une exclusion Git empêche certains ajouts ordinaires ; elle ne bloque pas la lecture d’un fichier par un outil.

Docmap est facultatif et sert à retrouver les textes. Il n’installe ni base ni service et ne modifie pas les autorisations de l’application.
