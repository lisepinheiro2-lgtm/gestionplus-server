# Mesure locale d’Argon2 — 14 novembre 2026

Mesure effectuée sur demande explicite, avec un programme Java autonome compilé et exécuté dans ce dossier temporaire. Aucun contexte Spring, accès à PostgreSQL, appel HIBP ou test de l’application n’a été lancé. Aucun fichier du dépôt n’a été modifié par le benchmark.

## Résultats regroupés

| Vérification | Nombre | Minimum | Médiane | Percentile 95 | Maximum | Moyenne |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Mot de passe correct | 600 | 19,417 ms | 22,759 ms | 26,411 ms | 32,100 ms | 23,017 ms |
| Mot de passe incorrect | 600 | 19,112 ms | 22,725 ms | 26,151 ms | 31,612 ms | 23,031 ms |

Le percentile 95 est calculé au rang supérieur : 95 % des valeurs sont inférieures ou égales à cette durée. Les résultats regroupent les échantillons bruts des trois JVM ; ce ne sont pas des moyennes de percentiles.

## Environnement

- Processeur : AMD Ryzen 5 5600X 6-Core Processor, 6 cœurs et 12 processeurs logiques.
- Mémoire physique visible : 15.92 Gio ; disponible avant la mesure : 2.59 Gio.
- Système : Microsoft Windows 11 Professionnel.
- JDK utilisé : Oracle JDK 25.0.2+10-LTS-69.
- Bibliothèques : Spring Security Crypto 7.1.2, Bouncy Castle 1.86, Commons Logging 1.3.6 et Spring Core 7.0.10, issues du cache Maven local du projet.
- Fabrique identique à celle de PasswordSecurityConfig : Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().
- Paramètres vérifiés depuis l’en-tête du hachage fictif : Argon2id, m=16384 Kio (16 Mio), t=2, p=1.
- Sel de 16 octets et résultat de 32 octets, selon cette fabrique.
- Options JVM par défaut ; tas maximal annoncé par chaque JVM : 4076 Mio.
- Activité de fond du poste non contrôlée.

## Protocole exécuté

1. Trois lancements successifs et indépendants de la JVM.
2. Un mot de passe fictif et un hachage fraîchement créé avant les mesures dans chaque JVM ; aucun hachage réel n’a été lu.
3. Cent vérifications d’échauffement par JVM, avec alternance entre succès et échec.
4. Deux cents mesures par cas et par JVM ; ordre des deux cas alterné pour répartir les effets d’ordre.
5. Chronométrage par System.nanoTime() autour du seul appel matches(). Le résultat booléen est vérifié après la mesure.
6. Aucun affichage, accès fichier ou hachage initial à l’intérieur de la zone chronométrée.
7. Toutes les valeurs conservées : 1200 vérifications mesurées et 300 vérifications d’échauffement.
8. Vérifications mesurées successives ; aucune mesure de charge concurrente.

Chaque processus Java s’est terminé avec le code 0. Les 1200 valeurs ont été contrôlées : 600 par cas, toutes strictement positives.

## Portée des chiffres

Sur ce poste et ces paramètres, l’appel de vérification Argon2 dure environ 23 ms au centre de la distribution, avec un percentile 95 voisin de 26 ms. Les résultats des mots de passe corrects et incorrects sont proches.

Ce relevé est un premier benchmark manuel local. Il inclut les pauses de la JVM et l’ordonnancement qui surviennent pendant matches(), et ne prétend pas mesurer un coût CPU pur ni une latence de connexion de bout en bout. Les lectures PostgreSQL, les filtres, l’audit et le réseau ne sont pas mesurés. Il faudra mesurer les parcours de connexion pour décider du recalage de la gigue, puis refaire les mesures sur le matériel de déploiement et dans les conditions de charge retenues.

Aucun paramètre Argon2 ni aucune plage de gigue n’a été changé.

## Fichiers conservés

- [Programme réutilisable](../../../../../tools/benchmarks/java/Argon2LocalBenchmark.java) et [mode d’emploi](../../README.md).
- [Environnement](environment.json) et [résultats regroupés](aggregate.json).
- Relevés bruts : [JVM 1](samples-fork-1.csv), [JVM 2](samples-fork-2.csv), [JVM 3](samples-fork-3.csv).
- Résumés : [JVM 1](summary-fork-1.json), [JVM 2](summary-fork-2.json), [JVM 3](summary-fork-3.json).

Ces preuves ont été transférées dans le dépôt après la mesure. Le programme réutilisable reçoit désormais des contrôles d’arguments et de non-écrasement des résultats et utilise directement la fabrique `PasswordSecurityConfig.passwordEncoder()`. Sa compilation seule a été validée après intégration, sans nouvelle mesure. Les empreintes des programmes d’origine sont conservées dans [original-programs.json](../original-programs.json).

## Références

- [Paramètres de la fabrique Spring Security](https://docs.spring.io/spring-security/reference/api/java/org/springframework/security/crypto/argon2/Argon2PasswordEncoder.html#defaultsForSpringSecurity_v5_8())
- [Horloge de mesure System.nanoTime](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/System.html#nanoTime())


