---
summary: "Repères du code Spring Boot et préparation d’une exécution locale sur base isolée."
read_when:
  - "explorer les couches Java JPA PostgreSQL et les migrations sans lancer le serveur"
  - "préparer ultérieurement un lancement ou des tests expressément autorisés"
---

# Explorer et lancer le serveur localement

<a id="reprise-2026-11-21"></a>

## Repères structures et vérificateurs — 21 novembre 2026

Sur `72aa7f4`, suivre `OrganizationalStructureRoutes` → `OrganizationalStructureController` → `OrganizationalStructureQueryService` → `OrganizationalStructureStore.findCandidatesByUser` → `JpaOrganizationalStructureStore` / `OrganizationalStructureJpaRepository`. Les deux GET sont authentifiés dans `HttpSecurityConfig` ; autorisations dans `AuthorizationService.rolesInStructureForSession`. `HttpApiError.fromAccessDenied` masque les structures étrangères/inaccessibles par 404. Aucune nouvelle migration.

Pour la gouvernance : `StructureAccessService`, `StructureAdminTransferService`, `SuperAdminSuccessionService`, `AccountAccessLifecycle` et `EmployeeResponsibilityService` existent ; parcours HTTP/Swing complets à raccorder. Pour l’activation : `HmacActivationIdentityVerifier` existe ; `MfaEnrollmentVerifier` et `PrivilegedConfirmationVerifier` attendent leurs implémentations opérationnelles. Premier enrôlement distinct du POST unique de connexion contenant le TOTP.

Lire `PwnedPasswordsChecker` et son test : validation des compteurs des lignes parcourues indépendamment de l’empreinte, traitement en `Long` dans ses bornes ; une correspondance positive termine encore la boucle.

`OrganizationalStructureHttpPostgresqlIntegrationTest` et `OrganizationalStructureQueryServiceTest` sont les repères des tests structures. Les **24 tests ciblés sur PostgreSQL jetable** précèdent les corrections. Les **19/19 HIBP** après revue utilisent un client HTTP simulé ; aucun serveur, PostgreSQL ni migration pour ces dernières vérifications. Voir [preuves attribuées, décisions et suites](AVANCEMENT.md#reprise-2026-11-21). Aucune commande de ce guide exécutée pendant cette mission.


<a id="essai-client-isole-2026-11-20"></a>

## Essai du client sur environnement isolé — 20 novembre 2026

**Historique du 20 novembre ; lire la reprise du 21 novembre ci-dessus pour l’état courant.**

Le client `03387cc` est raccordé à la connexion/session ; seule sa démo CSV reste autonome. Voir le [bilan et ses limites](AVANCEMENT.md#connexion-swing-2026-11-20).

Pour une recette autorisée, préparer une base séparée, une application sur `127.0.0.1` et une URL client correspondante. Le client utilise `http://localhost:8080` par défaut ; une autre URL se configure par `-Dgestionplus.server.url=...`. Le compte doit être ACTIVE, avec credential, rattachements cohérents et TOTP réel lorsque son rôle l'exige. Un SUPER_ADMIN désigné doit être rattaché à son entreprise et à son salarié ; son MFA reste obligatoire.

L'environnement de Lise et ses outils sont hors dépôt. Paramètres PostgreSQL, mots de passe, clés, liens de provisionnement et codes de récupération restent privés. Aucun compte par défaut ni migration de peuplement administrateur n'est livré ici.

L'amorçage d'une base fictive est une préparation administrative locale ; il ne valide pas le provisionnement en production, l'activation par SMS ou le parcours complet d'enrôlement MFA. Les clés et données de cet essai ne doivent pas être réutilisées pour un déploiement. HTTP local ne remplace pas les exigences TLS de la cible.

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

Contrôler la forme du numéro dans la classe responsable de l’écriture du téléphone. Pour le compte, `UserAccount.requirePhoneNumber` vérifie un `+` suivi de 1 à 15 chiffres ASCII à la construction et dans `setPhoneNumber`. Le message en français indique le format attendu. La validité du numéro et sa joignabilité relèvent de vérifications distinctes. Dans le parcours SMS, le garde Java contrôle la valeur du compte, puis le SQL de `PostgresqlActivationIdentityChallengeStore` conditionne l’enregistrement d’un échec et la consommation à l’état du défi et à l’échéance comparée à l’instant fourni. Les contraintes de V27 bornent ensuite les données stockées du défi, notamment la taille du champ téléphone, le compteur d’échecs et les états terminaux. [Jalon du 18 novembre](AVANCEMENT.md#garde-telephone-e164-2026-11-18)

Pour la création ajoutée sur `7bb9ad3` puis verrouillée sur `5a5d26f`, lire `ActivationIdentityChallengeService`, `ActivationCodeGenerator`, `ActivationCodeDigester` et `ActivationMessageSender`. Le service prépare un défi valable cinq minutes. Il applique un délai de renvoi de 60 secondes depuis la création tant que le défi courant reste utilisable. Le troisième code incorrect comptabilisé révoque le défi et rend le renvoi immédiatement possible dans la limite de trois défis par couple invitation et compte. Le défi révoqué reste compté. Le service relit le compte sous verrou et revérifie le délai et le quota avant de persister. La clé externe `gestionplus.activation.challenge-key` attend 32 octets en Base64. Les 18 méthodes unitaires et quatre scénarios PostgreSQL du service font partie du résultat local de 788/788 rapporté par Lise sur `5a5d26f`. [Création des défis et tests du service](AVANCEMENT.md#creation-defis-sms-2026-11-17)

Depuis `6c374a1`, `AccountActivationService.requestActivationChallenge(invitationID, token)` contrôle la forme du jeton, l’invitation, son échéance et son empreinte avant de déléguer à la création du défi. `isValidToken` est aussi réutilisée par `completeActivation`. L’action `INVITATION_SMS_REQUEST` permet l’audit des refus et des échecs. Depuis `48465e5`, le contrôleur transmet l’UUID et le jeton du corps JSON à ce service. Cet appel et sa délégation à `createChallenge` doivent avoir lieu hors transaction Spring englobante. [Contrat HTTP et accès](../README.md#demande-de-code-sms-par-http)

Les [tests de la demande de code SMS](AVANCEMENT.md#demande-code-sms-2026-11-17) ajoutent 10 méthodes unitaires, dont des méthodes paramétrées, et six scénarios PostgreSQL. Leur préparation génère un jeton distinct par test avec `InvitationTokenIssuer`, pour respecter l’unicité de `token_digest`. Lise confirme 838/838 tests réussis le 17 novembre après cette correction sur l’état local complétant `6c374a1` ; les deux classes sont depuis versionnées dans `3b3f78e`.

Depuis `e103f72`, `HmacActivationIdentityVerifier` recherche le défi courant, contrôle le téléphone et `isUsable`, puis compare l’empreinte recalculée avec `MessageDigest.isEqual`. Il renvoie l’UUID sans consommer le défi si le code correspond et enregistre un échec si le code diffère. La consommation reste dans `AccountActivationService.activateInTransaction`. `68ce7ef` ajoute `HmacActivationIdentityVerifierTest` (huit méthodes, dont une paramétrée) et `AccountActivationSmsPostgresqlIntegrationTest` (quatre scénarios). Dans cette dernière, le mock hérité délègue au vérificateur concret. L’expéditeur et le service de credentials restent simulés. [Jalon et portée des tests](AVANCEMENT.md#verification-hmac-sms-2026-11-18)

`AuditStorageIntegrationTest` conserve son `@MockitoBean` pour les tests. L’application dispose de `FakeActivationMessageSender` depuis `4008538`, activé uniquement par `gestionplus.sms.fictif=true`, sans envoi SMS réel. Le vérificateur HMAC est un composant Spring.

Avant le raccordement serveur–client, disposer de l’expéditeur minimal fictif pour les essais locaux. Après raccordement, intégrer le fournisseur SMS réel, valider son assemblage avec le vérificateur HMAC et inventorier les autres composants provisoires. [État des composants SMS](AVANCEMENT.md#beans-definitifs-apres-raccordement)

**Contrainte obligatoire pour l’appelant :** invoquer `createChallenge(invitationID)` **sans transaction Spring déjà active**, donc hors d’une méthode exécutée sous `@Transactional` et hors d’un `TransactionTemplate` actif. Le service conserve la propagation `REQUIRED` et ne contrôle pas automatiquement cette précondition ; un appel transactionnel pourrait envoyer avant le commit. Voir le [contrat d’appel détaillé](AVANCEMENT.md#contrat-appel-creation-defis-sms). Le compte est relu sous verrou et le délai/quota sont revérifiés avant le remplacement. L’invitation n’est pas relue à cet endroit et le téléphone utilisé reste celui lu avant verrou ; voir la [fenêtre résiduelle documentée](AVANCEMENT.md#telephone-avant-verrou-defis-sms).

### Simulation SMS locale et tests HTTP — 19 novembre 2026

Dans Eclipse, activer le simulateur par l’argument VM `-Dgestionplus.sms.fictif=true`, uniquement dans la configuration locale choisie. Il journalise le téléphone et le code : employer des données fictives. Le flag n’est pas activé par défaut dans les fichiers versionnés et aucune garde ne rejette automatiquement son utilisation avec `prod`. Avant un usage réel, désactiver le fictif et vérifier la sélection du fournisseur SMS réel.

Pour une vérification de démarrage, préparer une base PostgreSQL isolée et diriger `spring.datasource.url` vers cette base dans les arguments du programme. Flyway utilise alors la même source de données si aucune connexion de migration distincte n’est configurée. Avec Spring Boot 4.1.2, fournir seulement `spring.flyway.url` crée une connexion séparée sans reprendre automatiquement le mot de passe de l’application ; des identifiants Flyway explicites sont nécessaires pour ce mode. La séparation des identités du profil `prod` reste inchangée.

Les deux contrôles de démarrage ont été réalisés par Lise le 19 novembre sur une base locale isolée : démarrage réussi avec le flag, puis échec identifiant précisément le bean `ActivationMessageSender` manquant sans flag. Ces contrôles ne prouvent pas l’envoi d’un défi.

Depuis `4c630ee`, lire `CsrfHttpTest` pour les accès SMS et CSRF avec les vrais filtres, puis `SessionLifecycleHttpTest` pour les sessions. Ce sont des tests `@WebMvcTest`, avec services métier simulés, sans PostgreSQL. Les jetons proviennent du vrai endpoint CSRF ; la durée de session utilise le vrai `SessionLifetime` et une horloge pilotée. Le bilan global de **900/900** est une validation locale rapportée par Lise. [Détail des preuves](AVANCEMENT.md#securite-http-sessions-2026-11-19)

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

La configuration locale autorise les parcours de connexion/session et le POST SMS anonyme avec CSRF et jeton d’invitation. Les autres routes applicatives sont refusées par défaut. Les tests avec les filtres réels sont inclus dans le bilan 900/900 rapporté par Lise sur `4c630ee` ; Swing reste à raccorder. Voir les [accès HTTP](../README.md#accès-http-présents-dans-les-sources) et le [jalon du 19 novembre](AVANCEMENT.md#securite-http-sessions-2026-11-19).

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

Le dernier résultat global rapporté par Lise est de **900/900 tests réussis dans Eclipse le 19 novembre 2026 sur `4c630ee`**. Aucune exécution de l’assistant. [Chaîne HTTP, sessions et limites](AVANCEMENT.md#securite-http-sessions-2026-11-19)

Au raccordement, tenir compte du contrat hors transaction et des lectures de l’invitation et du téléphone avant verrou. Les réserves portent aussi sur le rattachement des audits SMS à l’entreprise, les instants utilisés après attente des verrous et le double échec de l’envoi et de sa compensation. [Réserves du 18 novembre](AVANCEMENT.md#reserves-sms-2026-11-18)

Aucune correction Java, configuration applicative ou migration n'est nécessaire pour simplement consulter ou publier les sources du chantier. Une éventuelle correction technique fait l'objet d'une intervention distincte.

Référence : [arguments du plugin Maven Spring Boot](https://docs.spring.io/spring-boot/maven-plugin/run.html).
