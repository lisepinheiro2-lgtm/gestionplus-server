---
summary: "Repères du code Spring Boot et préparation d’une exécution locale sur base isolée."
read_when:
  - "explorer les couches Java JPA PostgreSQL et les migrations sans lancer le serveur"
  - "préparer ultérieurement un lancement ou des tests expressément autorisés"
---

# Explorer et lancer le serveur localement

[Présentation](../README.md) · [Avancement et limites](AVANCEMENT.md)

Le code peut être étudié sans démarrer le serveur ni installer une base. Les commandes de lancement ci-dessous sont des instructions pour une exécution future ; elles n'ont pas été exécutées pendant la préparation de cette documentation.

## Repères dans le code

La racine Java est `src/main/java/fr/gestionplus/server/`.

| Dossier | Responsabilité actuelle |
| --- | --- |
| `auth/` | Comptes, états, credentials, connexion métier, rôles, permissions et raccordement Spring Security. |
| `account/` | Contrat de stockage des comptes et service de création directe actuellement désactivé. |
| `employee/` et `organization/` | Modèles métier et contrats de stockage des salariés et unités. |
| `persistence/` | Entités, repositories Spring Data et adaptateurs vers les modèles métier. |
| `integration/` | Vérification des mots de passe compromis via un service externe. |
| `audit/` | Premières pièces de la traçabilité ; persistance et raccordement encore incomplets. |

Commencer par `LoginService` pour le parcours métier de connexion, puis ses interfaces Lookup et leurs adaptateurs JPA. Lire `HttpSecurityConfig` pour connaître l'accès HTTP réellement configuré. Les migrations se trouvent dans `src/main/resources/db/migration/` ; les tests dans `src/test/`.

## Prérequis d'une exécution locale

- **JDK 25**, également sélectionné dans l'éditeur ou le terminal de lancement.
- **Maven Wrapper** fourni : il demande Maven **3.10.0** selon sa configuration. Son téléchargement et celui des dépendances nécessitent un accès aux dépôts de paquets. La disponibilité de cette distribution et la résolution complète n'ont pas été revérifiées ici.
- **PostgreSQL local** : la version de travail annoncée est 18 ; aucune matrice de compatibilité avec d'autres versions n'est établie.
- Deux bases distinctes pour le développement et les tests, contenant uniquement des données fictives.

Le dépôt ne fournit actuellement ni conteneur prêt à lancer, ni compte initial, ni jeu de données serveur préchargé, ni parcours complet d'activation.

## Préparer la base et l'environnement

Faire créer, avec les outils PostgreSQL de son choix, une base de développement `gestionplus_dev` et, pour les tests d'intégration, une base dédiée `gestionplus_test`. Utiliser un compte local dédié disposant des droits nécessaires sur ces bases pour les migrations ; éviter un compte de production ou un superutilisateur.

| Paramètre | Configuration livrée |
| --- | --- |
| Développement | `jdbc:postgresql://localhost:5432/gestionplus_dev` |
| Tests Spring portant le profil `test` | `jdbc:postgresql://localhost:5432/gestionplus_test` |
| Identifiant de connexion | Variable d'environnement `DB_USER` |
| Mot de passe | Variable d'environnement `DB_PASSWORD` |
| Schéma | Flyway activé ; validation Hibernate avec `ddl-auto: validate`. |

Définir `DB_USER` et `DB_PASSWORD` dans l'environnement du processus lancé, par exemple via la configuration d'exécution privée de l'éditeur. Ne pas mettre leurs valeurs dans le code, le pom, les commandes partagées ou un fichier versionné. Ne pas envoyer une capture de la configuration contenant ces valeurs.

Un fichier `.env` est exclu de Git, mais **aucun chargement automatique de ce fichier n'est configuré dans le projet**. Son existence ne suffit donc pas à fournir les variables.

Vérifier l'URL effective et les profils de lancement : une variable externe telle que `SPRING_DATASOURCE_URL` peut remplacer l'adresse attendue. Le profil de test partage par défaut les variables d'identification du développement ; il ne garantit pas à lui seul l'isolation d'une base.

**Le démarrage Spring et certains tests peuvent appliquer les migrations Flyway.** N'utiliser que des bases locales isolées dont le schéma peut évoluer. Une transaction de test annulée ne garantit pas l'annulation des migrations effectuées au chargement du contexte.

## Lancement local, lorsque l'environnement est prêt

Depuis la racine de ce dépôt, sous Windows PowerShell :

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--server.address=127.0.0.1"
```

Sous Linux ou macOS :

```sh
sh ./mvnw spring-boot:run -Dspring-boot.run.arguments=--server.address=127.0.0.1
```

Le wrapper sélectionne Maven, puis le plugin compile et lance l'application. L'argument limite l'écoute HTTP à cette machine sans modifier la configuration livrée. Arrêter avec `Ctrl+C`.

À ce stade, un serveur démarré ne propose pas de parcours de démonstration : **toutes les requêtes HTTP sont refusées par la règle `denyAll()`**. Aucune route de connexion ou API métier exploitable n'est annoncée. La réponse HTTP précise et le démarrage sur une nouvelle installation restent à vérifier ; ne pas retirer les contrôles pour obtenir artificiellement une page accessible.

Le contrôle des mots de passe compromis comporte un appel sortant vers Pwned Passwords lorsqu'il est invoqué. Les tests de cette intégration utilisent un client HTTP simulé ; cela ne prouve pas le comportement du service externe réel.

## Exécuter les tests ultérieurement

Après vérification de la base dédiée et des variables effectives :

```powershell
.\mvnw.cmd test
```

Équivalent Linux/macOS : `sh ./mvnw test`. Les tests d'intégration Spring présents portent `@ActiveProfiles("test")`. D'autres tests fonctionnent en mémoire ou avec des composants simulés.

Consulter `target/surefire-reports/` si le lancement Maven produit des rapports. Relever les réussites, échecs et interruptions : un code de sortie nul ou une classe présente ne prouve pas que tous les scénarios ont été exécutés. L'état effectivement connu reste décrit dans [AVANCEMENT.md](AVANCEMENT.md).

## Limites et suite des validations

Le prochain passage de validation devra notamment démontrer la construction sur une nouvelle installation, le parcours de connexion avec PostgreSQL isolé, les contraintes de rattachement et les refus attendus. La sécurité HTTP, les sessions et les permissions de portée demanderont des scénarios supplémentaires lorsqu'elles seront implémentées.

Aucune correction Java, configuration applicative ou migration n'est nécessaire pour simplement consulter ou publier les sources du chantier. Une éventuelle correction technique fait l'objet d'une intervention distincte.

Référence : [arguments du plugin Maven Spring Boot](https://docs.spring.io/spring-boot/maven-plugin/run.html).