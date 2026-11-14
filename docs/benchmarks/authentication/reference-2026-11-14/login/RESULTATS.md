# Mesure locale du parcours de connexion — 14 novembre 2026

La mesure réelle HTTP + PostgreSQL est terminée. Sur cette machine, la pause actuelle de 70 à 110 ms produit des refus sensiblement plus longs pour un identifiant inconnu ou un compte désactivé que pour un compte actif avec un mauvais mot de passe. Aucun réglage de l'application n'a été modifié.

## Résultats

150 mesures par cas, après 25 tentatives de chauffe par cas. Trois séries successives de 50 mesures par cas dans une même JVM ; ordre des cas alterné. Les valeurs sont en millisecondes.

| Cas | POST /login médiane | POST /login p95 | GET CSRF + POST /login médiane |
| --- | ---: | ---: | ---: |
| Connexion réussie, compte EMPLOYEE sans MFA | 47,43 | 68,96 | 50,75 |
| Compte actif, mauvais mot de passe | 41,43 | 50,66 | 44,64 |
| Identifiant inconnu | 97,03 | 116,82 | 100,23 |
| Compte désactivé | 104,51 | 121,44 | 107,63 |

La médiane est la valeur centrale. Le p95 est la valeur sous laquelle se trouvent 95 % des mesures, selon la méthode du rang supérieur. Le GET de préparation CSRF prend environ 3 ms en médiane pour chacun des cas. La durée totale inclut aussi la construction de la requête de connexion entre les deux échanges.

Les médianes par série du mauvais mot de passe sont 42,10 / 41,44 / 40,05 ms, contre 97,90 / 93,13 / 97,87 ms pour l'identifiant inconnu. Le constat est présent dans les trois séries.

## Interprétation

L'écart entre les médianes « identifiant inconnu » et « mauvais mot de passe » atteint 55,61 ms. Dans les 150 mesures de chaque groupe, le mauvais mot de passe le plus lent atteint 65,37 ms et l'identifiant inconnu le plus rapide 77,63 ms : ces deux plages observées ne se recouvrent pas.

Cette mesure établit donc une différence de temps observable dans cet environnement local. Elle ne mesure pas la possibilité de l'exploiter depuis Internet avec le réseau, le TLS, la charge et les limites de tentatives du déploiement réel.

Le précédent benchmark du seul Argon2 donnait environ 22,73 ms en médiane pour un mauvais mot de passe. Le parcours HTTP complet correspondant prend ici environ 41,43 ms. Ces deux expériences distinctes ne permettent pas d'attribuer exactement le reste à chaque composant.

La prochaine étape proposée est de recalibrer la pause à partir des parcours complets, puis de mesurer à nouveau les distributions. Cette exécution ne suffit pas à fixer une plage définitive valable en production. Le compte désactivé suit lui aussi un chemin différent ; réduire une constante commune ne garantit pas que tous les chemins s'alignent.

## Périmètre et protocole

- Copie temporaire des sources suivies, identiques au commit `e9fbd78` au moment de l'exécution ; empreintes dans `source-manifest.json`.
- Serveur Spring Boot réel, lié à `127.0.0.1` sur un port aléatoire, HTTP/1.1 sans TLS.
- Base PostgreSQL 16.15 neuve en conteneur Docker, avec les 26 migrations du projet. Aucun accès à la base de développement pour cette mesure.
- Comptes fictifs : 525 comptes mesurables/préchauffables plus un compte d'amorçage. Aucun envoi de courriel ; aucune donnée réelle.
- Une tentative à la fois. 100 tentatives de chauffe exclues des statistiques, puis 600 tentatives mesurées.
- Chaque tentative utilise un identifiant distinct et une adresse source distincte dans la boucle locale IPv4. Le limiteur de production reste actif ; ses seuils ne sont pas atteints. Cette exécution ne mesure pas son comportement sous attaque ou en charge.
- Un nouveau client HTTP et une nouvelle session par tentative ; le cookie obtenu par GET `/api/auth/csrf` est conservé pour POST `/login`.
- POST chronométré depuis l'envoi côté client jusqu'à la réception du corps de réponse. Les accès métier à PostgreSQL et l'audit du parcours font partie du traitement. Les requêtes supplémentaires de vérification de l'audit sont exécutées après l'arrêt du chronomètre.
- Chaque tentative est acceptée dans le relevé uniquement si le statut HTTP et l'unique événement d'audit associé à son adresse source correspondent au cas attendu.
- Le parcours de connexion réussie n'inclut ni code MFA, ni appel ultérieur à `/me`, ni interface Swing. Il ne représente pas tous les parcours d'authentification possibles.

## Environnement

- Windows 11 Pro ; AMD Ryzen 5 5600X, 6 cœurs physiques et 12 processeurs logiques ; environ 16 Gio de RAM installée.
- Oracle Java 25.0.2, options `-Xms128m -Xmx768m` ; threads virtuels activés.
- Spring Boot 4.1.2 ; pool Hikari limité à 10 connexions.
- Argon2id du projet inchangé : `m=16384,t=2,p=1`.
- Docker Desktop 29.9.2, image locale `postgres:16-alpine`, serveur PostgreSQL 16.15.
- Exécution du 14 novembre 2026, environ 20 h 52–20 h 54, heure de Paris. Le démarrage et les migrations sont exclus des durées publiées.

## Vérifications finales

- 600 mesures conservées, 100 tentatives de chauffe, 700 événements `AUTH_LOGIN` correspondants et aucun résultat `RATE_LIMITED`.
- Exécution terminée sans erreur ; serveur arrêté, port libéré, conteneur PostgreSQL et auxiliaire Testcontainers de cette exécution supprimés.
- Empreintes des sources toujours identiques au relevé ; dépôt serveur propre après l'exécution.
- Aucun fichier du projet, aucun suivi documentaire et aucun réglage de pause modifié par cette mesure.

## Fichiers de preuve

- [samples.csv](samples.csv) : durées brutes en nanosecondes, y compris la chauffe identifiée par la série 0.
- [summary.json](summary.json) : statistiques agrégées produites par le programme.
- [analysis.json](analysis.json) : résultats par série et comparaison des plages observées.
- [protocol.json](protocol.json) et [source-manifest.json](source-manifest.json) : protocole exécuté et provenance des sources.
- [Programme réutilisable](../../../../../tools/benchmarks/java/LocalLoginPathBenchmark.java) et [mode d’emploi](../../README.md).

Les journaux de compilation et d’exécution, le classpath, la copie de travail et les fichiers de contrôle temporaires ne sont pas publiés. Les contrôles d’arrêt et d’intégrité ont été exécutés avant le transfert et leur résultat est décrit ci-dessus.

Le programme réutilisable a été adapté après ce relevé : identifiants commençant par `fictitious_benchmark_`, contacts `example.invalid`, mot de passe fictif aléatoire conservé en mémoire, contrôles des arguments et de non-écrasement, identification de la pause à partir du composant réellement chargé. Les mesures historiques utilisaient les identifiants `bench_a_…` à `bench_d_…` et une autre valeur fictive de mot de passe. Le lanceur a été exécuté en mode compilation seule avec succès ; aucun benchmark n’a été relancé avec ces adaptations. Les résultats publiés restent ceux du programme d’origine, dont l’empreinte figure dans [original-programs.json](../original-programs.json).

