# Référence locale du 14 novembre 2026

Ces fichiers conservent deux mesures réalisées sur demande explicite, avant l’intégration de l’outillage au dépôt. Aucun réglage Argon2 ni aucune pause n’a été modifié pendant ces mesures.

| Mesure | Nombre d’échantillons hors chauffe | Résultat central | Preuves |
| --- | ---: | --- | --- |
| Argon2 seul | 1 200, répartis entre trois JVM | Mauvais mot de passe : médiane 22,73 ms, p95 26,15 ms. | [Rapport](argon2/RESULTATS.md), [résumé](argon2/aggregate.json), CSV et résumés par JVM dans `argon2/`. |
| Parcours HTTP et PostgreSQL | 600, dans une JVM après chauffe | Mauvais mot de passe : médiane 41,43 ms ; inconnu : 97,03 ms ; désactivé : 104,51 ms ; succès : 47,43 ms. | [Rapport](login/RESULTATS.md), [résumé](login/summary.json), [mesures brutes](login/samples.csv), [analyse par série](login/analysis.json). |

Le parcours HTTP porte sur les sources au commit `e9fbd78cb6fa3f620cd2163ca7a34e36a76481a5`, sans modification locale des sources mesurées. Les [empreintes des fichiers](login/source-manifest.json) et le [protocole](login/protocol.json) sont conservés. La mesure Argon2 utilise la même fabrique d’encodeur et les versions de bibliothèques indiquées dans son rapport ; elle n’est pas un démarrage du serveur.

Les CSV, résumés et relevés d’environnement sélectionnés sont conservés tels qu’ils ont été produits. Les rapports ont reçu des liens relatifs pour la publication et précisent les adaptations ultérieures du programme. Les [empreintes des programmes d’origine](original-programs.json) identifient les versions temporaires utilisées ; les programmes désormais dans `tools/benchmarks/` sont des versions réutilisables adaptées. Leur compilation seule a été validée après intégration ; les mesures n’ont pas été relancées avec ces versions.

Ces preuves montrent une différence locale entre les rejets, sans mesure d’exploitabilité réseau, de charge concurrente ou de TLS. Elles servent de référence au recalibrage et aux prochaines comparaisons, pas de seuil automatique de réussite dans les tests.

Consulter le [mode d’emploi](../README.md) pour refaire une mesure et produire un nouveau relevé daté. Les journaux bruts, le classpath, les copies compilées, les données du conteneur et les chemins temporaires du poste restent hors de cette archive.
