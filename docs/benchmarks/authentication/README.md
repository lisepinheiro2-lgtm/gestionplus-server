# Mesurer l’authentification

Ces outils manuels permettent de comparer les performances d’une machine à l’autre et d’évaluer un changement de la pause de connexion. Le mode retenu est isolé : il crée son propre serveur et sa propre base jetable, y compris lorsqu’il est lancé sur la machine destinée au déploiement.

Les [mesures de référence du 14 novembre 2026](reference-2026-11-14/README.md) comprennent les rapports, les CSV et les résumés JSON d’Argon2 seul et du parcours HTTP. Leur validité reste attachée au code et à l’environnement indiqués. L’ajout des outils réutilisables n’est pas une nouvelle mesure.

Les [nouvelles mesures après correction du mauvais mot de passe](after-login-delay-2026-11-14-947db46/RESULTATS.md), exécutées à 21 h 23–21 h 26 sur `947db46`, conservent 1 200 vérifications Argon2 et 600 tentatives HTTP hors chauffe. Le POST « mauvais mot de passe » prend 131,02 ms en médiane, contre 98,72 ms pour l’inconnu : la pause s’ajoute désormais à Argon2, avec un écart local restant d’environ 32 ms. Les relevés précédents restent archivés pour comparaison.

## Deux mesures disponibles

| Mode | Ce qui est exécuté | Ce qui est chronométré |
| --- | --- | --- |
| `Argon2` | Trois JVM successives, encodeur obtenu par la fabrique `PasswordSecurityConfig.passwordEncoder()`, sans contexte Spring démarré. | Uniquement `matches()` : 100 vérifications de chauffe puis 200 mesures correctes et 200 incorrectes par JVM, soit 1 200 mesures au total. |
| `Login` | Serveur Spring Boot réel, PostgreSQL 16 en conteneur neuf, migrations du projet et données fictives. | GET CSRF, POST `/login` et durée des deux échanges : 25 tentatives de chauffe puis 150 mesures pour chacun des quatre cas, soit 600 mesures hors chauffe. |

Le mode `Login` utilise HTTP/1.1 sur `127.0.0.1` avec un port aléatoire. TLS et l’exigence HTTPS sont désactivés dans ce seul contexte de mesure ; les YAML de l’application restent inchangés. Les threads virtuels sont activés et le pool Hikari est fixé à dix connexions. La pause et le limiteur du projet restent actifs. La classe de pause effectivement chargée et les empreintes des sources sont consignées pour éviter d’annoncer une ancienne plage après un recalibrage.

Le mode `Argon2` utilise la JVM sans options de tas ajoutées par le lanceur. Le mode `Login` conserve `-Xms128m -Xmx768m`, comme la mesure HTTP de référence.

## Comptes fictifs dédiés

Les données sont préparées dans la base neuve du conteneur, après vérification de son nom aléatoire `gestionplus_benchmark_…`. Il n’existe aucun script d’initialisation de ces comptes dans `src/main/resources` et aucune migration ne les ajoute au serveur habituel.

| Exemple d’identifiant | État et usage |
| --- | --- |
| `fictitious_benchmark_ok_login_000000` | Compte ACTIVE, rôle EMPLOYEE dans la structure fictive ; connexion réussie sans MFA. |
| `fictitious_benchmark_bad_pass_000000` | Compte ACTIVE, rôle EMPLOYEE ; tentative avec un mauvais mot de passe. |
| `fictitious_benchmark_notfound_000000` | Identifiant réservé au scénario inconnu : aucun compte correspondant n’est créé. |
| `fictitious_benchmark_disabled_000000` | Compte DISABLED, sans accès effectif à la structure. |
| `fictitious_benchmark_bootstrap` | Compte technique d’amorçage de l’entreprise fictive, sans credential ; aucune connexion avec ce compte. |

Chaque famille va de `000000` à `000174` : 25 identifiants pour la chauffe et 150 pour les mesures. Au total, 525 comptes de scénario et un compte d’amorçage sont créés. L’entreprise et la structure portent explicitement `FICTITIOUS BENCHMARK … - DISPOSABLE` ; noms et prénoms indiquent leur caractère fictif, les adresses sont sous `example.invalid` et les téléphones sont des zéros.

Le mot de passe commun des seuls comptes fictifs de scénario est généré aléatoirement à chaque exécution. Il reste en mémoire, n’est ni affiché ni écrit dans les résultats et disparaît avec le processus ; aucun mot de passe réel n’est requis. Aucune notification n’est préparée ou envoyée.

Chaque tentative utilise un compte ou identifiant distinct et une adresse source distincte dans `127.78.0.0/16`. Le programme vérifie le statut et le motif d’audit hors chronométrage ; un refus du limiteur ne peut pas être compté comme un mauvais mot de passe ou un compte inconnu. Ce choix permet de mesurer les parcours ordinaires avec le limiteur actif. Il ne mesure pas le limiteur en charge et ne s’applique pas à un serveur distant.

## Préparer le poste

- Un clone Git du serveur, JDK 25 et PowerShell 7 ou supérieur. `JAVA_HOME`, lorsqu’il est défini, doit désigner ce JDK ; sinon Java et le wrapper Maven utilisent l’environnement du poste.
- Les dépendances Maven du projet et le plugin `maven-dependency-plugin:3.8.1`. Le premier lancement peut télécharger le wrapper et les dépendances ; aucune dépendance applicative n’est ajoutée au `pom.xml`.
- Pour `Login` : Docker accessible et l’image `postgres:16-alpine` présente localement. L’outil ne télécharge pas cette image automatiquement. Si nécessaire, la préparer séparément avec `docker pull postgres:16-alpine`. Testcontainers peut aussi préparer son conteneur auxiliaire de nettoyage.
- Une période sans tests, builds ou travaux lourds concurrents. Relever la machine, la version effective de PostgreSQL et les conditions de charge pour chaque comparaison.

Le lanceur copie seulement les fichiers suivis de `src/main`, le POM et le wrapper dans un nouveau dossier de travail. Il ajoute les deux programmes de mesure à la copie, puis compile cette copie avec `test-compile`. Les tests du projet ne sont pas copiés et aucune phase Maven `test` n’est lancée. Les fichiers de configuration locaux ignorés par Git ne sont pas copiés. Les modifications des fichiers suivis sont incluses telles qu’elles existent au lancement ; les nouveaux fichiers applicatifs non suivis ne le sont pas. Le commit, l’état Git et les empreintes des fichiers copiés sont enregistrés.

## Lancer manuellement

Depuis la racine du dépôt :

```powershell
pwsh -File ./tools/benchmarks/Run-AuthenticationBenchmark.ps1 -Mode Argon2
```

Pour le parcours HTTP, cette commande autorise techniquement le lanceur à compiler sa copie, démarrer son serveur et son conteneur, appliquer les migrations et créer les comptes fictifs dans ce conteneur, effectuer les mesures puis fermer ses services :

```powershell
pwsh -File ./tools/benchmarks/Run-AuthenticationBenchmark.ps1 -Mode Login
```

Pour compiler la copie sans lancer de mesure, serveur, conteneur ou migration :

```powershell
pwsh -File ./tools/benchmarks/Run-AuthenticationBenchmark.ps1 -CompileOnly
```

Ajouter `-Offline` pour demander à Maven de travailler avec les artefacts déjà en cache. Cette option ne rend pas disponible une distribution du wrapper, un plugin ou une image Docker absents du poste.

Chaque exécution dispose de son propre dossier `target/benchmarks/<date-UTC-et-identifiant>/`, contenant `project/` et `results/`. Ils sont ignorés par Git. Le lanceur ne supprime pas les relevés précédents. La base et les comptes disparaissent avec le conteneur fermé en fin d’exécution ; le serveur local est fermé avant ce nettoyage. Un échec interrompt la mesure, garde éventuellement des données partielles et ne constitue pas une réussite.

## Lire et conserver les résultats

- `Argon2` : trois CSV, trois résumés par JVM et `aggregate.json`, recalculé à partir de tous les échantillons. Aucun hachage ou mot de passe dans ces fichiers.
- `Login` : `samples.csv` (série 0 = chauffe), `summary.json`, `run-environment.json` et `completed.txt`. Les motifs d’audit sont vérifiés pendant l’exécution. `completed.txt` n’est écrit qu’après la fermeture des services.
- Dans les deux cas : `source-state.json` et `source-manifest.json` identifient les sources et le mode utilisé. Les sorties locales peuvent contenir des chemins du poste ; relire et sélectionner les preuves avant publication.

Comparer la médiane, le p95 et les distributions, en conservant les valeurs brutes. Le p95 est calculé par la méthode du rang supérieur. Les chiffres de connexion incluent les filtres HTTP, les accès à PostgreSQL, Argon2 ou la pause et l’audit ; la préparation des données et les requêtes de vérification sont hors chronométrage.

Ce benchmark ne mesure ni le TLS, ni un reverse proxy, ni un appel à un serveur déjà déployé, ni les parcours MFA, ni la charge concurrente, ni l’interface Swing. Il ne prouve pas l’exploitabilité d’une différence de temps depuis Internet. Les résultats de référence ne fixent pas à eux seuls une plage de pause valable pour toutes les machines.

## État de validation

Les programmes temporaires d’origine ont été exécutés le 14 novembre 2026 ; leurs résultats sont archivés. Le lanceur et les adaptations versionnés ici sont ajoutés ensuite.

Le **14 novembre 2026 à 21 h 13, heure de Paris**, une compilation seule a été explicitement autorisée et exécutée avec `pwsh -NoProfile -File ./tools/benchmarks/Run-AuthenticationBenchmark.ps1 -CompileOnly -Offline`, JDK 25 et dépendances en cache : **BUILD SUCCESS**, 139 sources du serveur et les deux programmes compilés dans la copie isolée. La syntaxe PowerShell, les JSON, les liens locaux et les effectifs des CSV archivés ont aussi été contrôlés.

Aucun test, benchmark, serveur, conteneur ou migration n’a été lancé pendant cette vérification d’intégration de 21 h 13.

À la demande explicite de l’utilisatrice, les deux modes versionnés ont ensuite été compilés et exécutés à **21 h 23–21 h 26**, après enregistrement de la correction `947db46`. Les trois JVM Argon2 et le parcours HTTP terminent avec un code nul ; 1 200 vérifications et 600 tentatives hors chauffe sont conservées. Les 700 événements d’audit HTTP, chauffe comprise, ont les résultats attendus, sans limitation. Le serveur, le conteneur PostgreSQL et son auxiliaire de nettoyage sont arrêtés et supprimés ; le port du serveur est libéré. Cette exécution valide les parcours décrits par le benchmark, sans exécution de la suite JUnit ni validation d’un déploiement réel. Voir le [rapport et les preuves](after-login-delay-2026-11-14-947db46/RESULTATS.md).
