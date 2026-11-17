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
| `account/` | Cycle de vie des comptes (création en attente, activation, désactivation), contrat de stockage des défis SMS, verrous et versions de session, invitations avec livraison SMTP, accès aux structures, transfert d’administration et succession du `SUPER_ADMIN`. |
| `employee/` et `organization/` | Modèles métier et contrats de stockage des salariés et unités. |
| `persistence/` | Entités, repositories Spring Data et adaptateurs vers les modèles métier. |
| `integration/` | Vérification des mots de passe compromis via un service externe. |
| `audit/` | Événements, résolution de l’entreprise, persistance PostgreSQL, coordination transactionnelle, secours chiffré et réconciliation, interception des opérations `@AuditedOperation`. |

Commencer par `LoginService` pour le parcours métier de connexion, puis ses interfaces Lookup et leurs adaptateurs JPA. Lire `HttpSecurityConfig` pour connaître l'accès HTTP réellement configuré. Les migrations se trouvent dans `src/main/resources/db/migration/` ; les tests dans `src/test/`.

Pour l’activation après les refactorings `f5a7dbf` et `02a8f44` du 15 novembre 2026, commencer par `AccountActivationService`, désormais distinct de `AccountInvitationService`. `completeActivation` orchestre la validation de la demande, la pré-vérification de l’invitation, le contrôle du mot de passe, la preuve d’identité, puis `activateInTransaction`. `ActivationIdentityVerifier.verify` renvoie un `Optional<UUID>` avant la transaction d’activation ; le défi correspondant est relu, lié au compte, à l’invitation et au téléphone courant, puis consommé dans la transaction qui active le compte. Les [étapes et adaptations des tests](AVANCEMENT.md#activation-defis-sms-refactorisation-2026-11-15) sont détaillées dans le suivi.

Pour le stockage, lire `ActivationIdentityChallengeStore`, son record avec `createdAt`, l’adaptateur PostgreSQL et V27. Sur `e824b19`, `countByInvitationAndUser` compte tout l’historique et `revokeCurrent` compense un échec en `REQUIRES_NEW` sans rendre de place dans le quota ; vingt méthodes de tests du stockage sont présentes.

Pour la création ajoutée sur `7bb9ad3`, lire `ActivationIdentityChallengeService`, `ActivationCodeGenerator`, `ActivationCodeDigester` et `ActivationMessageSender`. Le service prépare un défi valable cinq minutes, vérifie un délai de 60 secondes et un quota de trois tentatives puis persiste avant l’appel de l’expéditeur. La clé externe `gestionplus.activation.challenge-key` attend 32 octets en Base64. Les [18 méthodes de tests nouvellement écrites et leurs limites](AVANCEMENT.md#creation-defis-sms-2026-11-17) distinguent simulation et PostgreSQL réel.

**Assemblage des tests et de l’application :** la base commune `AuditStorageIntegrationTest` fournit `ActivationMessageSender` via `@MockitoBean` aux contextes `@SpringBootTest` actuels. Ce mock est limité à `src/test` ; aucun expéditeur SMS concret n’est ajouté pour l’application. Le vérificateur concret et le parcours SMS complet ne sont pas livrés.

**Contrainte obligatoire pour l’appelant :** invoquer `createChallenge(invitationID)` **sans transaction Spring déjà active**, donc hors d’une méthode exécutée sous `@Transactional` et hors d’un `TransactionTemplate` actif. Le service conserve la propagation `REQUIRED` et ne contrôle pas automatiquement cette précondition ; un appel transactionnel pourrait envoyer avant le commit. Voir le [contrat d’appel détaillé](AVANCEMENT.md#contrat-appel-creation-defis-sms). Les préconditions et le quota restent contrôlés avant le verrou du remplacement ; leur coordination concurrente reste à consolider.

## Prérequis d'une exécution locale

- **JDK 25**, également sélectionné dans l'éditeur ou le terminal de lancement.
- **Maven Wrapper** fourni : il demande Maven **3.10.0** selon sa configuration. Son téléchargement et celui des dépendances nécessitent un accès aux dépôts de paquets. La disponibilité de cette distribution et la résolution complète n'ont pas été revérifiées ici.
- **PostgreSQL local** : la version de travail annoncée est 18 ; aucune matrice de compatibilité avec d'autres versions n'est établie.
- Une base de développement séparée des environnements de test, contenant uniquement des données fictives. Selon les classes, les tests utilisent une base dédiée ou un PostgreSQL Testcontainers jetable.

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

## Distinguer configuration locale et production

Les variables `DB_USER` et `DB_PASSWORD` du tableau précédent concernent la configuration locale. Le profil `prod` exige des variables distinctes, sans valeur de repli :

| Paramètre `prod` | Usage |
| --- | --- |
| `DB_URL` | URL PostgreSQL du déploiement. |
| `DB_APP_USER`, `DB_APP_PASSWORD` | Identité d’exécution du serveur. |
| `DB_MIGRATION_USER`, `DB_MIGRATION_PASSWORD` | Identité Flyway chargée des migrations. |

Cette séparation est présente dans `application-prod.yaml` ; elle ne prouve ni la création des rôles PostgreSQL, ni leurs droits effectifs, ni la réussite d’un déploiement. TLS, certificats, clés MFA/audit/invitation et SMTP restent à préparer hors dépôt. Les timeouts SMTP sont définis à 60 secondes pour la connexion, l’écriture et la lecture ; les envois utilisent une réservation durable et un guichet de trois tâches simultanées.

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

La configuration locale expose les premiers parcours Spring Security : `GET /api/auth/csrf`, `POST /login`, `GET /api/auth/me`, `GET /api/auth/session` et `POST /logout`, avec les contrôles de session et CSRF correspondants. Les autres routes applicatives restent refusées par défaut. Les services d’invitation, d’activation et de gouvernance ne disposent pas encore de parcours HTTP publics raccordés à Swing. Voir les [accès et limites du README](../README.md#accès-http-présents-dans-les-sources) et les [preuves datées](AVANCEMENT.md).

Le contrôle des mots de passe compromis comporte un appel sortant vers Pwned Passwords lorsqu'il est invoqué. Les tests de cette intégration utilisent un client HTTP simulé ; cela ne prouve pas le comportement du service externe réel.

## Exécuter les tests ultérieurement

Après vérification de la base dédiée et des variables effectives :

```powershell
.\mvnw.cmd test
```

Équivalent Linux/macOS : `sh ./mvnw test`. Certains tests Spring utilisent le profil `test` et sa base dédiée ; d’autres, notamment les classes d’invitation, définissent une URL Testcontainers et démarrent leur propre PostgreSQL jetable. Lire la configuration de la classe avant tout lancement : le profil `test` seul ne garantit pas l’isolation. Les tests unitaires utilisent aussi des composants en mémoire ou simulés. Les programmes de benchmark ont leur propre environnement isolé et ne constituent pas la suite JUnit.

Consulter `target/surefire-reports/` si le lancement Maven produit des rapports. Relever les réussites, échecs et interruptions : un code de sortie nul ou une classe présente ne prouve pas que tous les scénarios ont été exécutés. L'état effectivement connu reste décrit dans [AVANCEMENT.md](AVANCEMENT.md).

Les [benchmarks manuels d’authentification](benchmarks/authentication/README.md) disposent d’un protocole distinct, avec serveur et base jetables, comptes fictifs et preuves archivées. Ils ne mesurent pas le TLS, le WAN ni la charge concurrente.

## Limites et suite des validations

Le dernier résultat global disponible est **745/745 réussis dans Eclipse, confirmés par Lise le 16 novembre sur `9c1a5a0`**. Les 723/723 du 15 novembre sur `02a8f44` restent un jalon distinct. Aucun de ces résultats ne valide automatiquement `e824b19`, `7bb9ad3` ou les tests ajoutés le 17 novembre. Ces nouveaux scénarios sont écrits, non exécutés ; aucun test, build, serveur ou migration lancé par l’assistant. L’expéditeur concret, le vérificateur, l’assemblage Spring, les garanties transactionnelles et concurrentes et l’intégration Swing restent à compléter ; voir [les limites datées](AVANCEMENT.md#creation-defis-sms-2026-11-17).

Aucune correction Java, configuration applicative ou migration n'est nécessaire pour simplement consulter ou publier les sources du chantier. Une éventuelle correction technique fait l'objet d'une intervention distincte.

Référence : [arguments du plugin Maven Spring Boot](https://docs.spring.io/spring-boot/maven-plugin/run.html).
