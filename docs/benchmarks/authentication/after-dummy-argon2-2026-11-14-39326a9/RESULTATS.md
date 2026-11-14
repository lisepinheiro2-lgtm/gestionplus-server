# Comparaison des rejets après équilibrage Argon2 — 14 novembre 2026

Les deux modes ont été compilés et exécutés successivement en isolé sur **`39326a995bf94652a27e69991e118f438bf0362f`**, sans modification locale des sources. Les commandes commencent à 21 h 50 et terminent à 21 h 53, heure de Paris. Le dépôt était propre après les mesures, avant leur transfert documentaire.

## Résultats demandés

150 mesures par cas, après 25 tentatives de chauffe, dans trois séries alternées de 50 mesures par cas. Durées du POST `/login`, en millisecondes, incluant le traitement HTTP, PostgreSQL et l’audit.

| Rejet | Médiane précédente, `947db46` | Médiane actuelle, `39326a9` | p95 actuel |
| --- | ---: | ---: | ---: |
| Mauvais mot de passe | 131,02 | 127,55 | 146,44 |
| Identifiant inconnu | 98,72 | 120,25 | 143,75 |
| Compte désactivé | 103,55 | 123,70 | 144,05 |

L’écart entre les trois médianes extrêmes passe de **32,31 ms à 7,30 ms**. Le mauvais mot de passe reste 7,30 ms au-dessus de l’inconnu et 3,85 ms au-dessus du compte désactivé. Les p95 sont regroupés entre 143,75 et 146,44 ms. Les plages min–max des trois cas se recouvrent de 104,45 à 150,24 ms.

Le correctif rapproche donc les durées observées, mais cette exécution donne des médianes d’environ **120 à 128 ms**, et ne démontre pas une égalité des distributions. La valeur absolue attendue de 131 ms provenait d’une autre exécution ; l’activité du poste et les temps JVM varient.

## Résultats par série

| Série | Mauvais mot de passe | Inconnu | Désactivé |
| --- | ---: | ---: | ---: |
| 1 | 128,62 | 123,43 | 122,34 |
| 2 | 137,41 | 116,86 | 125,47 |
| 3 | 125,22 | 117,94 | 122,54 |

Ces variations comptent dans l’interprétation : la deuxième série garde un écart d’environ 20,54 ms entre mauvais mot de passe et inconnu. Une moyenne ou une médiane globale ne suffit pas à affirmer que les cas sont indiscernables. Aucun test d’exploitabilité à distance, de charge concurrente ou d’équivalence statistique n’est revendiqué.

Les données sont conservées dans [samples.csv](login/samples.csv), le [résumé complet](login/summary.json) et l’[analyse comparative](login/analysis.json).

## Autre mode et parcours réussi

Le mode Argon2 a terminé ses trois JVM sans erreur : **1 200 vérifications mesurées**, avec 100 vérifications de chauffe par JVM. Médiane correcte : **17,86 ms**, p95 23,33 ms ; médiane incorrecte : **17,69 ms**, p95 23,62 ms. Paramètres inchangés : `argon2id`, `m=16384,t=2,p=1`.

Ce mode chronomètre `matches()` seulement. Le coût de l’`encode()` factice est inclus dans les parcours HTTP inconnu et désactivé ; il n’est pas chronométré séparément par le microbenchmark.

[Résumé Argon2](argon2/aggregate.json), [état des sources Argon2](argon2/source-state.json) et CSV dans le même dossier.

Le parcours HTTP réussi, sans MFA, reste à 42,05 ms en médiane et 59,70 ms au p95. La préparation CSRF prend environ 2,6 ms en médiane. Les médianes CSRF + POST des trois rejets sont respectivement 130,76 / 122,88 / 126,39 ms.

## Exécution et limites

```powershell
pwsh -NoProfile -File ./tools/benchmarks/Run-AuthenticationBenchmark.ps1 -Mode Argon2 -Offline
pwsh -NoProfile -File ./tools/benchmarks/Run-AuthenticationBenchmark.ps1 -Mode Login -Offline
```

JDK Oracle 25.0.2, Windows 11 Professionnel, AMD Ryzen 5 5600X, 12 processeurs logiques, environ 15,92 Gio de RAM visible et 1,98 Gio disponibles au relevé avant exécution. Aucun processus Java ou conteneur concurrent observé avant les commandes ; l’activité du bureau reste non contrôlée. Le mode Argon2 utilise le tas par défaut, maximum annoncé de 4 076 Mio ; le mode Login utilise `-Xms128m -Xmx768m`.

Le serveur est réel, lié à `127.0.0.1`, HTTP/1.1 sans TLS. PostgreSQL 16.15 est neuf et jetable, avec les 26 migrations appliquées à cette seule base. Les comptes sont fictifs et explicitement nommés, le limiteur reste actif ; une nouvelle adresse locale et un nouvel identifiant sont utilisés pour chaque tentative.

Les **600 mesures HTTP hors chauffe** et les 100 tentatives de chauffe ont les statuts et motifs d’audit attendus, avec 700 événements `AUTH_LOGIN` et aucun refus `RATE_LIMITED`. Les compilations et les deux modes terminent avec succès. La suite JUnit habituelle n’a pas été exécutée.

La modification des quatre chemins de rejet est visible dans les sources. Ce benchmark couvre les deux chemins factices « inconnu » et « désactivé », et le vrai Argon2 « mauvais mot de passe ». Les deux autres chemins factices, compte non actif et credential absent ou incohérent, ne sont pas des cas supplémentaires de ce programme.

Le serveur est arrêté et son port libéré ; le conteneur PostgreSQL et l’auxiliaire Testcontainers de cette exécution sont supprimés. Les empreintes des sources des deux copies sont toujours identiques au dépôt et les deux relevés identifient `39326a9`. Voir la [vérification finale](login/verification.json), l’[état des sources HTTP](login/source-state.json), les [empreintes](login/source-manifest.json) et le [marqueur de fin](login/completed.txt).

Les preuves issues de `target/benchmarks/` sont archivées ici après la mesure, sans nouvelle exécution. Les CSV, les résumés JSON, les états des sources et les vérifications sont copiés à l’identique ; seuls les liens et la présentation de ce rapport sont adaptés à leur emplacement dans le dépôt. Les journaux, classpaths et copies compilées restent dans les sorties locales ignorées. Aucun code ni paramètre de sécurité n’est modifié pendant cette mise à jour documentaire.

Voir le [relevé précédent sur `947db46`](../after-login-delay-2026-11-14-947db46/RESULTATS.md) et le [mode d’emploi](../README.md).
