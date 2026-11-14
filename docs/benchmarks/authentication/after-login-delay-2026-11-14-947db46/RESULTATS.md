# Nouvelle mesure après ajout de la pause sur le mauvais mot de passe

Mesures exécutées le **14 novembre 2026, de 21 h 23 à 21 h 26, heure de Paris**, à la demande de l’utilisatrice, après enregistrement de sa correction. Les sources mesurées sont celles du commit **`947db46a7c392ed9e15749787998b3f52e6f76d2`**, avec un dépôt propre au lancement des deux modes.

Les deux benchmarks se terminent avec un code de sortie nul : **1 200 vérifications Argon2 et 600 tentatives HTTP hors chauffe**. La suite JUnit habituelle n’a pas été exécutée. Aucun code, réglage Argon2 ou intervalle de pause n’a été modifié pendant cette intervention.

## Correction effectivement mesurée

Dans `LoginService.evaluate()`, un mauvais mot de passe sur compte actif passe maintenant par `rejectAfterDelay(...)` après l’échec de la vérification réelle du mot de passe. La pause du projet reste comprise entre 70 et 110 ms. Les chemins « compte inconnu » et « compte désactivé » utilisent cette même pause sans effectuer de vérification Argon2.

Cette correction change le comportement de l’application. Le protocole des outils versionnés reste inchangé pendant la nouvelle mesure.

## Argon2 seul

Trois JVM successives, chacune avec 100 vérifications de chauffe puis 200 mesures par cas. Les statistiques regroupent les 600 valeurs de chaque cas, et non les percentiles des trois JVM.

| Vérification | Nombre | Minimum | Médiane | p95 | Maximum |
| --- | ---: | ---: | ---: | ---: | ---: |
| Mot de passe correct | 600 | 18,14 ms | 20,73 ms | 24,44 ms | 28,96 ms |
| Mot de passe incorrect | 600 | 18,13 ms | 20,75 ms | 24,41 ms | 29,87 ms |

Le relevé précédent donnait 22,73 ms de médiane pour le mauvais mot de passe. Les paramètres sont toujours `argon2id`, `m=16384,t=2,p=1`. Les conditions d’activité du poste ne sont pas contrôlées : cet écart entre exécutions ne suffit pas à établir un gain logiciel.

Preuves : [résumé regroupé](argon2/aggregate.json), [JVM 1](argon2/samples-fork-1.csv), [JVM 2](argon2/samples-fork-2.csv), [JVM 3](argon2/samples-fork-3.csv), [état des sources](argon2/source-state.json) et [empreintes](argon2/source-manifest.json).

## Parcours HTTP et PostgreSQL

Une JVM, 25 tentatives de chauffe par cas, puis trois séries de 50 mesures par cas : 150 valeurs conservées pour chacun des quatre parcours. Les mesures sont successives et leur ordre est alterné.

| Cas | POST `/login` médiane | POST `/login` p95 | GET CSRF + POST médiane |
| --- | ---: | ---: | ---: |
| Connexion réussie, EMPLOYEE sans MFA | 46,70 ms | 58,49 ms | 49,53 ms |
| Compte actif, mauvais mot de passe | 131,02 ms | 152,52 ms | 134,13 ms |
| Identifiant inconnu | 98,72 ms | 117,38 ms | 101,82 ms |
| Compte désactivé | 103,55 ms | 121,74 ms | 106,50 ms |

Le GET CSRF prend environ 2,9 ms en médiane. Le chronométrage du POST inclut les échanges HTTP, les filtres, les accès à PostgreSQL, Argon2 ou la pause et l’audit. Les contrôles supplémentaires de l’audit sont hors chronométrage. Le p95 utilise la méthode du rang supérieur.

Les 700 tentatives, chauffe comprise, ont chacune produit le statut HTTP et le motif d’audit attendus. Les 700 événements `AUTH_LOGIN` sont présents ; aucun résultat `RATE_LIMITED` n’a été admis ou observé.

## Comparaison avec le relevé précédent

| Médiane du POST | Référence `e9fbd78` | Après correction `947db46` | Différence |
| --- | ---: | ---: | ---: |
| Succès | 47,43 ms | 46,70 ms | −0,74 ms |
| Mauvais mot de passe | 41,43 ms | 131,02 ms | +89,60 ms |
| Inconnu | 97,03 ms | 98,72 ms | +1,69 ms |
| Désactivé | 104,51 ms | 103,55 ms | −0,96 ms |

Le ralentissement du mauvais mot de passe est cohérent avec l’ajout de la pause de 70–110 ms après Argon2. Le mauvais mot de passe est désormais **32,31 ms plus lent en médiane que l’identifiant inconnu**, et 27,47 ms plus lent que le compte désactivé. L’ancien écart avec l’inconnu avait le sens inverse : le mauvais mot de passe était 55,61 ms plus rapide.

Les plages observées « mauvais mot de passe » et « inconnu » se recouvrent maintenant de 109,53 à 119,76 ms. Ce recouvrement ne prouve pas que les distributions sont indiscernables. Dans les trois séries, les médianes du mauvais mot de passe sont 134,31 / 130,83 / 129,12 ms, contre 96,29 / 99,13 / 99,64 ms pour l’inconnu : l’écart reste visible localement.

Cette comparaison est descriptive. Entre la référence et cette exécution, le programme a été intégré au dépôt avec des identifiants fictifs explicites et un mot de passe fictif aléatoire. Les versions et les empreintes des programmes sont conservées ; l’activité de fond du poste n’est pas identique ou contrôlée. Les différences de médianes ne sont pas des chronométrages internes séparés de la pause ou d’Argon2.

La correction est donc mesurée, mais elle ne suffit pas à établir l’égalisation des rejets. Une éventuelle autre stratégie d’alignement reste à décider et à mesurer. Aucune correction supplémentaire n’est appliquée ici.

## Environnement et limites

- Windows 11 Professionnel, AMD Ryzen 5 5600X, 12 processeurs logiques, environ 15,92 Gio de mémoire visible ; 2,36 Gio disponibles au relevé précédant les mesures. Aucun processus Java ni conteneur concurrent n’a été observé à ce moment ; l’activité générale du bureau reste non contrôlée.
- Oracle Java 25.0.2 ; Argon2 utilise les options de tas par défaut, avec 4 076 Mio de maximum annoncé. Le mode HTTP utilise `-Xms128m -Xmx768m`.
- PostgreSQL **16.15** dans un conteneur neuf, les **26 migrations** appliquées à cette seule base ; threads virtuels activés et pool Hikari de dix connexions.
- HTTP/1.1 sur boucle locale IPv4, serveur lié à `127.0.0.1`, sans TLS. Limiteur et pause actifs, un identifiant et une adresse source distincts par tentative.
- 525 comptes de scénario et un compte d’amorçage, tous fictifs et nommés explicitement ; mot de passe aléatoire en mémoire, aucun courriel envoyé.
- Aucun test de charge concurrente, proxy, WAN, MFA ou Swing. L’exploitabilité depuis Internet n’est pas mesurée. Les chiffres ne constituent pas une plage de pause directement applicable à toute production.

Voir le [relevé d’environnement](environment.json), le [résumé HTTP](login/summary.json), l’[analyse par série](login/analysis.json), les [échantillons bruts](login/samples.csv) et les [sources effectivement copiées](login/source-manifest.json).

## Commandes et vérifications finales

Après sélection du JDK 25 dans l’environnement du processus, depuis la racine du dépôt, dans cet ordre et sans exécution simultanée :

```powershell
pwsh -NoProfile -File ./tools/benchmarks/Run-AuthenticationBenchmark.ps1 -Mode Argon2 -Offline
pwsh -NoProfile -File ./tools/benchmarks/Run-AuthenticationBenchmark.ps1 -Mode Login -Offline
```

Les deux copies isolées compilent avec `BUILD SUCCESS`, puis les deux modes terminent sans erreur. Le serveur HTTP est fermé et son port libéré ; le conteneur PostgreSQL et l’auxiliaire Testcontainers de cette exécution sont absents après vérification. Les empreintes des sources sont identiques à celles du relevé, et le dépôt est encore propre immédiatement après les mesures, avant l’ajout de cette documentation.

La [vérification finale](login/verification.json), le [marqueur de fin](login/completed.txt), les états des sources et les CSV sont archivés. Les journaux de compilation, classpaths, fichiers de travail et données de la base jetable restent dans les sorties locales ignorées ou ont été supprimés avec le conteneur. Les [mesures de référence](../reference-2026-11-14/README.md) sont conservées.
