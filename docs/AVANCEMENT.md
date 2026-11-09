---
summary: "État serveur au 9 novembre 2026. Audit des rattachements, migrations locales V7 à V12 et résultats de tests."
read_when:
  - "reprendre les comptes authentification rôles permissions sessions et audit"
  - "distinguer code présent tests écrits résultats disponibles et fonctions prévues"
  - "préparer les décisions DEC01–DEC06 et l’isolation multi-structures"
  - "anticiper MFA limitations des connexions SSH TLS et ADMIN_EMERGENCY"
---

# Avancement et feuille de route

État initial relu le **27 octobre 2026 (Europe/Paris)**, complété par les décisions du 29 octobre et du 2 novembre. **Actualisation technique le 2 novembre 2026** : fichiers Java présents sur `main` et résultats JUnit ciblés montrés depuis Eclipse. **Complément au 3 novembre 2026** : avancement du code *local* signalé par captures Eclipse et validations JUnit ciblées ; les nouvelles classes et la migration V8 ne sont pas supposées déjà poussées sur GitHub. Les mises à jour documentaires ne lancent ni test, ni build, ni serveur, ni migration.

<a id="inscription-et-rattachements-2026-11-09"></a>
## Comptes et accès aux structures — 9 novembre 2026

**Décisions validées.** Un compte global par salarié, avec des rôles propres aux rattachements dans la même entreprise cliente. Le `SUPER_ADMIN` conserve son compte en l’absence de rattachement à une structure. La création d’un rattachement exige `MANAGE_USERS` et `MANAGE_USER_ROLES`, la supériorité stricte sur chaque rôle demandé et les contrôles de périmètre. L’accès à ces permissions pour `MANAGER` et `SECRETARY` lors d’un nouveau rattachement actualise la règle historique du 5 novembre. La première invitation part après enregistrement de tous les accès validés. Sept jours après son envoi effectif, le serveur invalide l’invitation non utilisée et, si le compte est toujours en attente, le désactive et retire atomiquement tous les rattachements et rôles préparés pour cette inscription. Le parcours est audité et doit résister à une activation concurrente.

| État | Constat |
| --- | --- |
| Services locaux | `StructureAccessService` ajoute et révoque les rattachements individuels. `StructureAdminTransferService` porte le transfert ADMIN et la consultation associée. `AccountManagementServiceTest` conserve son nom historique. |
| Ajout de rattachement | `addStructureAccess()` vérifie la demande, les deux permissions, le compte et la structure cibles, l’appartenance du salarié à l’entreprise, le doublon et la hiérarchie de tous les rôles demandés. La méthode enregistre le rattachement et [audite les rôles attribués](#audit-rattachements-2026-11-09). |
| Politique locale | `RolePermissionPolicy` accorde les deux permissions à `MANAGER` et `SECRETARY`, comme à `R_H`, `ADMIN` et `SUPER_ADMIN`. `TEAM_LEAD`, `EMPLOYEE` et `ACCOUNTANT` en sont dépourvus. L’attribution reste soumise à la hiérarchie et au périmètre de l’acteur. |
| Résultats de tests | Suite annoncée verte après la séparation des services. Après le raccordement de l’audit, l’utilisatrice rapporte un nouveau lancement local avec **356/356 tests réussis**. |

**À développer.** Les recherches et la reprise autorisée de fiches dans Swing, l’inscription, les invitations, l’activation, l’expiration et la réactivation restent à réaliser. Le cycle du compte relève d’une responsabilité distincte de `StructureAccessService`. La coordination et les invitations concurrentes, le sort de `TenantMembership`, le mécanisme d’expiration, le lien exact fiche-compte et les invitations pour un compte déjà actif restent ouverts.

<a id="audit-rattachements-2026-11-09"></a>
## Audit des rattachements aux structures — 9 novembre 2026

- **Code serveur.** L’attribution et la révocation d’un rattachement enregistrent les rôles exacts dans un instantané immuable et typé. L’audit conserve les cibles compte et structure, ainsi que l’identité authentifiée, l’adresse IP et l’heure obtenues côté serveur. Les appels historiques à `record()` restent compatibles.
- **Transaction et concurrence.** L’ajout prend le verrou de la structure avant la vérification du doublon. Le rattachement et l’audit de succès participent à la même transaction, avec propagation d’un échec d’écriture.
- **Schéma local.** Flyway a appliqué les six migrations en attente, de V7 à V12, sur la base locale de développement. Le contrôle final a validé V1 à V12, la colonne nullable `audit_events.metadata` de type `JSONB` et les clés primaires composites des rattachements et de leurs rôles. Une ligne historique sans métadonnées se lit avec un instantané vide.
- **Tests.** Quatre classes de tests ajoutées ou adaptées couvrent le contexte serveur, l’immuabilité, l’historique JPA, les refus et l’isolation, ainsi que le rollback et la concurrence sur PostgreSQL. L’utilisatrice rapporte **356/356 tests réussis** lors de son lancement local. Le contrôle Flyway est une vérification distincte du schéma.

Le périmètre concerne les rattachements individuels. La révocation des sessions et le cycle de vie du compte après un retrait restent des chantiers distincts, ainsi que l’intégration Swing.

<a id="tests-autorisations-2026-11-05"></a>
## Tests adaptés aux contrats locaux — 5 novembre 2026

**Écrit : 8 fichiers de tests modifiés et 2 ajoutés, exclusivement sous src/test. Compilation et exécution : aucune.** Les sources locales actuelles ont servi de référence, sans utiliser l’ancienne archive du serveur. Les résultats des 2–3 novembre conservés plus bas concernent leurs versions datées ; ils ne valident pas les tests adaptés aujourd’hui.

Les services existaient avant cette adaptation : `AccountManagementService.revokeStructureAccess(targetUserId, structureID)` obtient l’acteur via `AuthenticatedUserProvider`, puis vérifie l’autorisation et la hiérarchie avant de retirer la seule appartenance visée et d’enregistrer un audit de succès. `AuthorizationService.canInStructure` recharge le compte, contrôle son état, la structure et son entreprise ; les rôles ordinaires proviennent des appartenances. Le SUPER_ADMIN doit être celui du tenant propriétaire. La hiérarchie Spring distingue notamment MANAGER/SECRETARY et place ACCOUNTANT sous ADMIN, hors de la chaîne ordinaire. Il s’agit d’une lecture du code, pas d’une validation de ces parcours.

| Fichier sous src/test/java/fr/gestionplus/server/ | Action | Méthodes déclarées | Périmètre écrit |
| --- | --- | ---: | --- |
| `account/AccountManagementServiceTest.java` | modifié | 30 | Identité Spring et compte courant, dépendances, auto-révocation, protection ADMIN/SUPER_ADMIN, hiérarchie, permissions et entreprise, audit de succès et autres accès préservés. |
| `auth/AuthorizationServiceTest.java` | modifié | 23 | Contrat injecté, compte/structure/tenant relus, droits propres à la structure, SUPER_ADMIN limité à son entreprise et interdit comme rôle de structure. |
| `organization/OrganizationalStructureTest.java` | modifié | 6 | Constructeur avec tenantID, conservation de l’entreprise et refus de null. |
| `persistence/OrganizationalStructureEntityTest.java` | modifié | 3 | Conservation du tenant lors des conversions métier/JPA. |
| `persistence/OrganizationalStructureJpaPersistenceTest.java` | modifié | 1 | Création du salarié, du SUPER_ADMIN et du tenant avant la structure ; relecture du rattachement. |
| `persistence/StructureMembershipJpaPersistenceTest.java` | modifié | 1 | Fixtures rattachées à un tenant pour les appartenances et rôles distincts. |
| `persistence/JpaStructureMembershipStorePostgresqlIntegrationTest.java` | modifié | 3 | Fixtures avec tenant ; retrait d’une appartenance ordinaire sans altérer le compte, l’autre accès ni les structures. |
| `persistence/StructureSchemaPostgresqlIntegrationTest.java` | modifié | 14 | V8/V9 et fixtures tenants ; codes SQL précis pour clés étrangères, unicité, NOT NULL et noms vides. |
| `auth/RoleHierarchyPolicyTest.java` | ajouté | 4 | Hiérarchie Spring réelle, rôles incomparables et obligation de dominer tous les rôles de la cible. |
| `persistence/TenantOrganizationEntityTest.java` | ajouté | 2 | Conversion du tenant, conservation de son identité, de son nom et de son SUPER_ADMIN. |

**87 méthodes déclarées dans ces dix classes**, dont plusieurs paramétrées : ce nombre décrit les sources concernées, pas un nombre de cas JUnit exécutés ni l’inventaire de toute la suite. Les tests de service utilisent la vraie politique de permissions et la hiérarchie Spring avec des stockages simulés. Les tests PostgreSQL conservent leurs bases Testcontainers dédiées ; aucune connexion ni migration n’a été lancée.

### Vérifications réalisées et limites

- Lecture des contrats actuels et relecture statique des tests ; anciens appels aux méthodes supprimées remplacés.
- Lors de l’adaptation des tests, comparaison des empreintes sous src : huit modifications, deux ajouts, aucun fichier supprimé et aucun changement sous src/main par cette adaptation. Le contrôle final de la documentation a ensuite relevé une évolution distincte du repository de structure, précisée ci-dessous.
- Aucun résultat de compilation ou d’exécution, aucun rapport JUnit nouveau. Les 87 méthodes et la suite globale restent non exécutées ; aucune réussite ni conformité complète de sécurité n’est annoncée.
- Aucune source de production, migration ou configuration modifiée. Cette actualisation documentaire ne modifie pas les tests une seconde fois et ne lance ni build, serveur, application, migration ou opération Git modifiante.

### Points de production laissés ouverts

- **Verrouillage, constat actualisé au dernier contrôle :** la déclaration de `OrganizationalStructureJpaRepository.findByIdForUpdate(UUID)` lue au début ne portait pas de requête explicite. Le fichier a évolué pendant cette mise à jour documentaire, avec ajout de `@Query("SELECT s FROM OrganizationalStructureEntity s WHERE s.structureID = ?1")` en conservant `@Lock(PESSIMISTIC_WRITE)`. L’absence de requête signalée initialement est donc levée dans la déclaration relue ; sa compilation et le verrouillage réel restent non vérifiés. Cette évolution de production est distincte des écritures documentaires de l’assistant.
- `AccountManagementService.revokeStructureAccess` ne déclenche pas explicitement `SessionRevocationService` et ne journalise actuellement que le succès. La révocation effective des sessions concernées et l’audit des refus restent à raccorder et vérifier.
- Le contrôle d’un MANAGER porte ici sur la structure, sa permission et la hiérarchie des rôles ; aucun rattachement personnel de la cible à ce manager n’est contrôlé dans cette méthode. Les tests de hiérarchie ne prouvent pas ce périmètre métier.
- V8/V9 ne garantissent pas en stockage l’unicité d’un ADMIN par structure, l’interdiction de SUPER_ADMIN dans les rôles de structure ni la présence du rôle global SUPER_ADMIN sur le compte référencé par un tenant. Le refus applicatif du rôle de structure est couvert dans les tests écrits, sans démontrer une contrainte SQL.
- V9 ajoute `tenant_id` puis impose `NOT NULL` sans rattacher les structures préexistantes : une table déjà peuplée nécessite un traitement explicite. Les fixtures neuves ne valident pas cette montée de version.

**Prochaine petite étape proposée, sur demande distincte :** valider la compilation puis l’exécution ciblée des tests sur environnement isolé, en tenant compte de la requête de verrouillage désormais présente et encore non vérifiée. Les résultats attendus des tests écrits sont le refus sans retrait ni audit de succès des opérations interdites, le retrait de la seule appartenance autorisée avec audit compte/structure, et la conservation des autres accès et liens tenant. Les contraintes SQL doivent échouer pour leur cause précise. Aucune correction de production ni exécution n’est autorisée par cette note. L’intégration Swing, la fermeture complète de structure et le mode ADMIN_EMERGENCY hors ligne restent à concevoir ou valider séparément.

<a id="autorisations-2026-11-05"></a>
## Reprise prioritaire — autorisations et administration — 5 novembre 2026

**Décisions fermes de conception ; aucune nouvelle preuve d’implémentation.** La [référence commune du 5 novembre](https://github.com/lisepinheiro2-lgtm/gestionplus-client/blob/main/docs/vision-ensemble.md#decisions-securite-2026-11-05) précise les anciennes décisions. **Sécuriser d’abord AuthorizationService et les autorisations côté serveur, avant de poursuivre l’authentification et l’intégration Swing.** Les anciennes prochaines actions et l’ordre des tableaux restent historiques lorsqu’ils contredisent cette priorité.

| Sujet | Cible retenue |
| --- | --- |
| Déploiement | Spring Boot/PostgreSQL local chez l’entreprise **ou** distant ; aucun hébergement externe obligatoire. Les frontières d’autorisation sont les mêmes. |
| Entreprise et SUPER_ADMIN | Plusieurs structures possibles par entreprise cliente ; **exactement un SUPER_ADMIN**, créé exclusivement par la développeuse à la mise en service, limité aux structures de cette entreprise. Vérifier le rattachement à l’entreprise propriétaire de la structure ; la présence du rôle ne suffit pas. |
| ADMIN et suppression | **Exactement un ADMIN nominatif par structure**, jamais supprimable individuellement même par SUPER_ADMIN. Seul le SUPER_ADMIN de l’entreprise peut supprimer une structure ; retirer le rattachement ADMIN et tous les accès à cette structure. Préserver les autres appartenances du compte global multi-structures. Anticiper conservation, transactions/concurrence, sessions, accès hors ligne et synchronisations. |
| Identité et rôles | Acteur issu de l’identité authentifiée Spring ; compte rechargé au serveur et état contrôlé ; rôles de **StructureMembershipStore** dans la structure demandée. Aucun rôle/UUID librement fourni par Swing ne fait autorité. Le stockage des appartenances ne remplace pas la politique d’autorisation serveur. |
| Hiérarchie et permissions | Hiérarchie via Spring Security, permissions métier indépendantes. ACCOUNTANT hors chaîne ordinaire mais sous ADMIN/SUPER_ADMIN ; MANAGER/SECRETARY peuvent être incomparables. ADMIN/SUPER_ADMIN : tous les droits métier dans leur périmètre, avec les réserves de cycle de vie ci-dessus. RH modifie les rôles inférieurs autorisés ; ADMIN peut gérer RH. SECRETARY crée/édite les contrats et invite, sans modifier les rôles ni désactiver les comptes. |
| ADMIN_EMERGENCY | **MODE de secours hors ligne** de chaque ADMIN pour sa propre structure, sur poste préautorisé ; aucun compte ou rôle ordinaire supplémentaire. Authentification sécurisée, données locales chiffrées, traces protégées, droits limités et réconciliation. Pas de connaissance immédiate d’une révocation pendant la déconnexion ; validité limitée et renouvellement à définir. |

**À préciser :** graphe exact et matrice des droits, rôles initiaux d’invitation, mise en service/récupération des comptes uniques, remplacement de l’ADMIN sans suppression individuelle, conservation et fermeture cohérente, compte sans rattachement, révocation ciblée et opérations/durées/clés/conflits hors ligne.

**Non implémenté ou non vérifié par cette intervention :** ces garanties complètes d’entreprise/structure, unicité des administrateurs, suppression sécurisée de structure, hiérarchie raccordée et mode de secours. Les jalons techniques antérieurs sont conservés avec leurs dates et limites ; leur présence ne démontre pas ces parcours complets. Aucun code, test, build, serveur, migration, commit, push ou publication effectué pour cette mise à jour.

## Comment lire les statuts

- **Implémenté** : code présent et examiné ; pas de promesse de fonctionnement global.
- **Vérifié sur un périmètre précis** : résultat d'exécution disponible, avec sa date et ses limites.
- **En cours** : pièces présentes, assemblage ou validation encore incomplets.
- **Prévu** : objectif de conception, sans parcours opérationnel annoncé.

## État des blocs

Les chemins ci-dessous sont relatifs à `src/main/java/fr/gestionplus/server/`. Le tableau conserve les jalons historiques. Le [bilan du 5 novembre](#tests-autorisations-2026-11-05) et la [mise à jour de l’audit du 9 novembre](#audit-rattachements-2026-11-09) détaillent les évolutions et leurs validations.

| Bloc | Présent dans les sources | État et limite |
| --- | --- | --- |
| Comptes et credentials | `auth/UserAccount`, `UserAccountStatus`, `UserCredential` ; UUID distinct du salarié ; états en attente, actif et désactivé. | Implémenté au niveau du modèle. Invitation et activation complète à construire. |
| Authentification métier et HTTP | `auth/LoginService`, `GestionAuthenticationProvider`, Lookups JPA ; `HttpSecurityConfig` et contrôleurs `api/`. | Connexion formulaire HTTP Spring Security opérationnelle dans les tests ciblés ; refus des identifiants erronés et des comptes désactivés vérifié sur PostgreSQL isolé. Pas encore de parcours réseau réel avec Swing. |
| Mots de passe | `PasswordPolicy` : 12 à 128 caractères, espaces significatifs ; contrôle de compromission ; `CredentialService` et encodeur Argon2. | Implémenté, validation globale à poursuivre. Créer un credential ne le persiste pas à lui seul. |
| Vérification de compromission | `integration/PwnedPasswordsChecker` interroge le service Pwned Passwords avec un préfixe d'empreinte ; sept tests avec client HTTP simulé. | Code et tests présents ; comportement réel, réponses multiples et cas limites à valider. Cette empreinte de recherche ne remplace pas Argon2 pour le stockage. |
| Spring Security / CSRF / déconnexion | `auth/HttpSecurityConfig`, `api/CsrfController`, `api/AuthSessionController` : `GET /api/auth/csrf`, `POST /login`, `GET /api/auth/me`, `POST /logout`. | Implémenté et testé par MockMvc : `200` connexion et consultation autorisée, `401` échec/sans session, `204` déconnexion, refus `403` sans CSRF. Les autres requêtes restent refusées par `denyAll()`. Autorités Spring encore vides ; pas de permissions métier exposées. HTTPS, cookies et paramètres de déploiement restent à valider. |
| Rôles et permissions | `RolePermissionPolicy` et `AuthorizationService` ; comptes non actifs refusés ; cumul de rôles. | Première politique implémentée. Les rôles incluent `R_H`, mais sa politique dédiée et les contrôles hiérarchiques restent à définir et appliquer. |
| Persistance des comptes | Entités, repositories, Lookups et `persistence/JpaAccountStore` ; en local le 3 novembre : `AccountStore.findById()`, `disableAccount()`, `UserAccountEntity.disable()`. | Désactivation et recherche par UUID vérifiées par 3 tests PostgreSQL ciblés. **`AccountManagementService` ne désactive pas encore réellement** ; périmètre, dernier administrateur, transaction et révocation associée restent à sécuriser. Création directe toujours désactivée. |
| Salariés et unités | Modèles `Employee` et `OrganizationalUnit`, stores, entités JPA et référence d'un salarié vers une unité. | Socle implémenté ; trois tests de conversion vérifiés sur le périmètre décrit ci-dessous. Persistance complète à valider. |
| Schéma | Migrations V1 à V12, dont V7 pour l’audit, V8 à V11 pour les structures et entreprises et V12 pour les métadonnées d’audit. | [V1 à V12 validées sur la base locale de développement](#audit-rattachements-2026-11-09). Les résultats ciblés antérieurs sur V8 figurent dans l’historique des tests. |
| Audit | `AuditService`, `AuditMetadata`, `AuditStore`, `persistence/JpaAuditStore`, entités et repository d’audit. | L’ajout et la révocation d’un rattachement tracent leurs rôles dans [l’audit de succès](#audit-rattachements-2026-11-09). Le raccordement des autres événements suit leurs parcours métier. |
| Client Swing connecté | Projet client distinct, actuellement autonome en CSV. | Intégration prévue ; aucun échange avec ce serveur dans le démarrage client examiné. |
| Sessions HTTP et identité de compte | Contexte HTTP et identité stable par `userID` ; en local le 3 novembre : bean `SessionRegistry`, `maximumSessions(-1)`, stratégie d'expiration `401`, `SessionRevocationService`. | **5/5 tests HTTP/PostgreSQL ciblés** : deux sessions d'un même compte enregistrées dans le registre, révoquées via le service, puis refusées (`401`). Un test unitaire vérifie que les sessions d'un autre compte restent actives. **Pas de déclenchement métier automatique**, ni de garantie de révocation multi-serveur ou en cas de panne. |
| Invitations, récupération, MFA et `ADMIN_EMERGENCY` | Objectifs de conception distincts. | Parcours complets à construire et vérifier ; aucune disponibilité annoncée. |

La configuration actuelle contient bien `spring.jpa.hibernate.ddl-auto: validate` et un profil de test PostgreSQL. Les anciens constats sur une indentation incorrecte ou l'absence de profil ne décrivent donc plus les fichiers actuels. Cela ne prouve pas leur bon fonctionnement à l'exécution.

## Tests : sources et résultats sont deux informations différentes

Les campagnes ci-dessous restent historiques. Le [bilan du 5 novembre](#tests-autorisations-2026-11-05) décrit les tests adaptés à cette date. Le [bilan du 9 novembre](#audit-rattachements-2026-11-09) consigne séparément le lancement local de 356 tests réussi par l’utilisatrice.

Inventaire du **27 octobre** : **15 classes et 59 méthodes annotées `@Test`** à cette date ; chiffre historique, **non présenté comme l'inventaire actuel**. De nouvelles classes de tests ont depuis été publiées. Un fichier présent sur GitHub et un résultat vert local sont deux preuves distinctes.

| Périmètre | Résultat disponible | Ce que cela démontre |
| --- | --- | --- |
| `OrganizationalUnitEntityTest` et `EmployeeEntityTest` | Rapport local du 27 octobre 2026 : **3 réussites** lors d'un lancement ciblé JUnit fourni par l'éditeur. | Conversions en mémoire, conservation des champs et des identifiants dans ces cas. |
| `EmployeeOrganizationJpaPersistenceTest` | Dix scénarios écrits ; exécution interrompue avant leurs assertions de persistance lors de la même campagne. | **Persistance non validée par cette campagne.** Ces dix cas ne sont pas comptés comme réussis. |
| Authentification, autorisations, credentials, repositories et vérification de compromission | Tests présents ; pas de rapport actuel de réussite globale retenu ici. | Couverture à examiner et exécutions à confirmer. Les succès historiques isolés ne valent pas validation des sources actuelles. |
| Cycle Maven complet | Dernière tentative ciblée documentée : arrêt avant les tests, dépendance de lancement absente du cache hors ligne. | Aucun résultat global vert revendiqué. |
| `CsrfHttpTest` (MockMvc, services simulés) | Capture JUnit Eclipse du **2 novembre 2026 : 9/9 réussis**, aucune erreur ni échec. | CSRF, connexion HTTP, conservation du contexte, refus d'accès sans session, renouvellement du token CSRF, déconnexion individuelle et refus de déconnexion sans token sur le périmètre MVC simulé. |
| `LoginHttpPostgresqlIntegrationTest` (Spring Boot + Testcontainers PostgreSQL) | Capture JUnit Eclipse du **2 novembre 2026 : 4/4 réussis**, aucune erreur ni échec. | Vrai stockage PostgreSQL isolé, migrations Flyway, credential Argon2, login actif, mauvais mot de passe, compte initialement désactivé et **deux sessions HTTP distinctes** pour un même compte ; vérification externe de compromission simulée. |
| `SessionRegistryIdentityTest` (unitaire) | Capture JUnit Eclipse du **2 novembre 2026 : 1/1 réussi** après correction de `equals()` / `hashCode()` ; premier lancement rouge attendu (`2` contre `1`). | Deux instances de `UserAccount` partageant le même UUID sont regroupées dans un `SessionRegistryImpl` créé **manuellement dans le test**. Ce test ne prouve pas le suivi automatique des vraies sessions. |
| Sessions HTTP + révocation ciblée (`LoginHttpPostgresqlIntegrationTest`) | Captures Eclipse du **3 novembre 2026 : 5/5 réussis**. | Registre connecté aux deux sessions HTTP MockMvc ; appel au `SessionRevocationService` ; refus `401` aux deux requêtes suivantes. Pas de désactivation réelle déclenchante. |
| `SessionRevocationServiceTest` | **1/1 réussi** (3 novembre, JUnit local). | Deux sessions d'Alice expirent ; la session de Bob reste active. |
| `UserAccountDisablePostgresqlIntegrationTest` | **3/3 réussis** (3 novembre, JUnit + Testcontainers). | Modification persistée et relue en PostgreSQL ; désactivation via `AccountStore` ; recherche par UUID et absence correctement signalée. Transaction de test annulée à la fin. |
| `AccountManagementServiceTest` | **2/2 réussis** (3 novembre, Mockito). | Refus de `EMPLOYEE` sans `MANAGE_USERS` et de la cible inconnue. **Aucune désactivation métier autorisée n'est implémentée**, ni contrôle de structure ou de dernier administrateur. |
| Modèles et conversions multi-structures (`OrganizationalStructureTest`, `StructureMembershipTest`, `OrganizationalStructureEntityTest`, `StructureMembershipIdTest`, `StructureMembershipEntityTest`) | **5/5 + 6/6 + 3/3 + 3/3 + 2/2 réussis** (3 novembre, tests unitaires locaux). | Modèles, rôles par structure, identité composée et conversions en mémoire. Pas encore de validation JPA de `StructureMembershipEntity`. |
| V8 et structures PostgreSQL (`StructureSchemaPostgresqlIntegrationTest`, `OrganizationalStructureJpaPersistenceTest`) | **5/5 + 1/1 réussis** (3 novembre, Testcontainers). | Tables et contraintes V8, affectation de rôles sur deux structures, écriture/lecture JPA de `OrganizationalStructure`. |
| Autorisations réellement isolées par structure, révocation automatiquement déclenchée, réseau Swing et suite globale | Aucun test concluant disponible pour ces fonctions. | À construire et à valider avant ouverture d'opérations sensibles. |

Les rapports et captures ciblés proviennent des exécutions locales présentées ; les fichiers générés et journaux bruts ne sont pas publiés dans ce document. **Bilan additionnel du 2 novembre : 9 + 4 + 1 = 14 tests réussis sur trois classes**, lancées séparément. Cela **ne constitue pas une suite complète verte**, ni un test HTTP sur réseau réel depuis Swing. Aucun test n'a été exécuté pendant cette actualisation documentaire.

## Feuille de route

**Ordre historique :** la [priorité du 5 novembre](#autorisations-2026-11-05) impose désormais de sécuriser les autorisations avant de poursuivre l’authentification et l’intégration Swing ; la règle d’ADMIN unique protégé remplace la seule protection du dernier administrateur.

L'ordre pourra évoluer selon les dépendances découvertes. Aucune date de livraison n'est promise.

| Étape | Travail restant | Critère de passage |
| --- | --- | --- |
| 1. Stabiliser le socle | Reproduire la construction et les tests ; vérifier les migrations et contraintes sur une base isolée ; consolider salariés, unités et rattachements. | Résultats reproductibles, environnement décrit et limites explicites. |
| 2. Finaliser comptes et authentification | Premier administrateur contrôlé, invitation liée à une fiche salarié, choix personnel des identifiants, activation par e-mail et SMS, récupération et désactivation. | Succès et refus testés ; liens/codes temporaires, activation cohérente et absence de secret dans les traces. |
| 3. Appliquer rôles et permissions | API, droits sur chaque opération, périmètres de responsabilité, gestion des rôles multiples et protection du dernier administrateur. | Refus vérifiés entre rôles et périmètres, y compris en concurrence ; contrôles imposés côté serveur. |
| 4. Consolider sessions et sécurité | Expiration, rotation et révocation, protections HTTP, limitation des tentatives, MFA des comptes privilégiés et gestion des secrets. | Tests de session et de refus ; politique de récupération définie ; revue avant exposition réseau. |
| 5. Rendre l'audit exploitable | Persistance protégée, événements rattachés aux opérations, accès et durée de conservation définis. | Traces utiles et contrôlées, sans mot de passe, code ou jeton. |
| 6. Relier le client Swing | Contrats d'échange, connexion, erreurs et compatibilité des versions ; conserver une démonstration autonome isolée. | Parcours connectés testés, aucun accès privilégié obtenu par repli local. |
| 7. Préparer la mise à disposition | Validation de sécurité, données exclusivement fictives pour la démo, instructions reproductibles, versions compatibles et distribution. | Publication du service décidée séparément de celle des sources ; accès de démo limité et environnement isolé. |

L'accès de secours `ADMIN_EMERGENCY` reste à concevoir séparément : poste autorisé, droits limités, audit et révocation. Il ne remplace pas un administrateur central et ne doit pas devenir une bascule permettant de contourner les permissions.

## Décisions métier transversales — 29 octobre 2026

**Conception précisée, aucune implémentation supplémentaire revendiquée.** Le détail fait référence dans la [vision commune DEC01–DEC06](https://github.com/lisepinheiro2-lgtm/gestionplus-client/blob/main/docs/vision-ensemble.md#decisions-metier-2026-10-29).

| Repère | Conséquence à anticiper côté serveur |
| --- | --- |
| DEC01 — Structures indépendantes | Isoler salariés, clients, planning, interventions, remplacements et documents, y compris recherches/exports. Compte unique multi-structures ou comptes distincts : décision ouverte. Ne pas confondre structure et unité existante ; schéma d'isolation non choisi. |
| DEC02 — Rôles et permissions | Responsables liés et secrétaires dans leur structure et leur périmètre ; matrice par action à préciser. Ne pas déduire des droits documentaires ou de gestion des comptes des seuls droits métier. |
| DEC03 — Historique et facturation | Documents conservés après départ selon les règles à définir ; intervention annulée conservée avec facturation à déterminer, puis décision habilitée facturée/non facturée. Alerte distincte à prévoir pour le passé non attribué ; aucune preuve de réalisation déduite de l'attribution seule. |
| DEC04 — Travail simultané | Verrou temporaire serveur avec information de l'utilisateur et consultation possible, complété par un contrôle de concurrence à l'enregistrement. Refuser l'écrasement silencieux ; portée, expiration et reprise à concevoir. |
| DEC05 — ADMIN_EMERGENCY | Continuité maximale sur poste autorisé, sous restrictions restant à décider. Données locales chiffrées, authentification sécurisée, audit et réconciliation ; pas d'accès illimité ni de contournement de la révocation ou des permissions serveur. |
| DEC06 — Documents sensibles | Administrateur : accès complet à sa structure ; comptable : documents nécessaires à sa mission. Catégories et opérations à préciser ; stockage, conservation et protections à concevoir. Aucun accès aux mots de passe/codes/jetons secrets. |

La matrice de droits, les durées de conservation, la validation de facturation, le cycle de vie des verrous, les opérations hors ligne et certains choix de stockage restent ouverts. **Choix DEC01 précisé le 3 novembre : un compte global peut appartenir à plusieurs structures, avec des rôles différents dans chacune.** Le compte global et la suppression de l'accès à une structure doivent être gérés séparément. Les `OrganizationalUnit` ne désignent pas ces structures indépendantes. Adapter explicitement les décisions antérieures de comptes, de hiérarchie et de dernier administrateur à cette cible.

Cette mise à jour ne change pas les états des sources ni les preuves de tests du 27 octobre. Aucun test, build, serveur, migration ou opération Git modifiante exécuté.

## Décisions d'architecture — 2 novembre 2026 : connexion Swing et sessions

**Statut de cette section : décisions d'architecture prises le 2 novembre, avant leur réalisation technique.** Le plan A–G ci-dessous est conservé comme référence historique ; les premières réalisations et preuves du même jour sont détaillées dans la section « Avancement technique — 2 novembre 2026 ». Ne pas confondre le plan initial avec l'état actuel des sources.

### Choix retenus et responsabilités

- **Authentification :** réutiliser Spring Security et son mécanisme éprouvé de connexion par formulaire HTTP (sans page HTML dans Swing), avec `GestionAuthenticationProvider` → `LoginService` → vérification des credentials. Ne pas créer de vérification parallèle des mots de passe dans une API personnalisée.
- **Échanges client/serveur :** client de bureau Swing sur HTTPS ; formulaire HTTP pour l'envoi initial des identifiants, API **JSON** pour les données métier, l'identité de la session et les erreurs utiles au client. JSON et Spring Security sont complémentaires, non concurrents.
- **Sessions :** authentification conservée côté serveur ; Swing transmet le **cookie de session** lors des requêtes suivantes, sans renvoyer ni stocker durablement le mot de passe. Prévoir cookie protégé (`Secure`, `HttpOnly`, politique `SameSite` appropriée), expiration, prévention de la fixation de session et protection **CSRF**, y compris pour le client Swing. Les réponses de connexion/erreur doivent être exploitables sans navigation ou redirection HTML.
- **Autorisation :** décisions imposées **côté serveur** par opération et par périmètre de structure. L'interface Swing ne confère aucun droit. Aucune opération métier sensible ne doit devenir accessible tant que ses autorisations ne sont pas appliquées et testées. Ne pas confondre les `OrganizationalUnit` actuelles avec les futures structures indépendantes (DEC01).
- **Révocation impérative :** désactivation de compte, changement **ou réinitialisation** de mot de passe → révocation de **toutes** les sessions du compte, y compris celle ayant initié le changement et celles des autres appareils. Un changement de rôle/permissions doit également rendre les sessions concernées inexploitables jusqu'à réévaluation des droits. Refus serveur dès que la modification est effective ; affichage immédiat de la déconnexion dans Swing = sujet ergonomique distinct (notification ou contrôle périodique). Examiner les opérations simultanées.
- **Comptes :** respecter le parcours déjà décidé : fiche salarié préalable et invitation habilitée, choix des identifiants par la personne, contrôles des secrets, activation e-mail/SMS, récupération ; ne pas réactiver la création directe de comptes comme raccourci. Pour les comptes privilégiés, **MFA avant accès privilégié**.
- **Audit :** tracer les connexions significatives, échecs pertinents, déconnexions, révocations et actions sensibles, selon la politique de confidentialité et de conservation à définir ; jamais de mot de passe, cookie, jeton ni code de vérification dans les traces. Réutiliser le socle d'audit existant sans assimiler « stockage disponible » et « événements métier raccordés ».
- **Évolutivité :** commencer sobrement avec les sessions de Spring Security pour une seule instance ; prévoir un moyen cohérent de retrouver/révoquer les sessions si plusieurs instances sont déployées un jour, sans imposer dès maintenant Redis ou une infrastructure distribuée.
- **`ADMIN_EMERGENCY` :** demeure un mécanisme hors ligne **séparé**, réservé à un poste autorisé, avec restrictions, chiffrement et audit. Il ne doit pas devenir un contournement de la sécurité serveur.

### Ordre de développement prévu (pas un calendrier ni une autorisation d'exécution)

| Ordre | Étape et portée | Condition de validation / dépendance |
| --- | --- | --- |
| **A — maintenant** | Préparer le **premier parcours HTTP de connexion**, en environnement isolé, avec un compte fictif actif fourni par des données de test ; conserver les contrôles de `LoginService` et `GestionAuthenticationProvider`. Définir le contrat de réponse sans page HTML. | Identifiants valides acceptés ; identifiants faux et compte non actif refusés, sans révéler la cause précise. Pas de contournement par création directe ni d'ouverture d'API métier. |
| **B — avec le premier parcours** | Raccorder le **form login Spring Security**, la persistance du contexte authentifié dans une session serveur, la lecture d'identité (`/api/auth/me` envisagé) et la déconnexion ; adapter les statuts/réponses pour Swing. Traiter HTTPS/cookies, CSRF, fixation de session et gestion des erreurs dès cette étape. | Une connexion réussie est reconnue sur la requête suivante ; sans session et après déconnexion, accès refusé. Tests de session et d'échec ; `denyAll()` reste protecteur tant que les seules exceptions nécessaires ne sont pas délimitées et vérifiées. |
| **C — avant toute donnée métier sensible** | Intégrer les contrôles d'autorisation dans les points d'entrée serveur ; préciser les droits par rôle, action et **structure**, en tenant compte de DEC01–DEC02 et du dernier administrateur. | Tests d'accès autorisé/interdit, de séparation entre structures et de concurrence ; impossibilité de déduire les autorisations de l'affichage Swing ou d'un rôle seul. |
| **D — création des comptes réels** | Concevoir puis implémenter premier administrateur contrôlé, invitation par personne habilitée liée à un salarié, choix personnel des identifiants, activation e-mail + SMS, expiration et anti-abus ; prévoir récupération et MFA des comptes privilégiés. | Parcours d'activation et de refus testés, aucune création directe incontrôlée ; accès privilégié impossible avant MFA. |
| **E — au moment des opérations sur comptes/secrets, avant utilisation réelle** | Révocation de toutes les sessions après désactivation, changement/réinitialisation de mot de passe et changements de permissions ; contrôler l'état actif et la validité des autorisations côté serveur. Raccorder l'audit. | Vérifier plusieurs sessions/appareils, session ayant initié le changement, accès concurrents et comportement après révocation ; aucune ancienne session ne permet une nouvelle opération autorisée. |
| **F — après sécurisation du serveur** | Connecter l'interface Swing : envoi des identifiants, conservation du cookie de session en mémoire, gestion du CSRF, affichage des erreurs et de la déconnexion, appels API JSON. | Scénarios de bout en bout, sans secret persistant ni repli local donnant des droits serveur. |
| **G — avant exposition/publication** | Revue sécurité globale : limitation des tentatives, sessions, MFA, secrets, audit, concurrence, compatibilité des versions et déploiement ; décision séparée pour `ADMIN_EMERGENCY`. | Résultats de tests documentés sur leur périmètre, refus sensibles vérifiés et aucune prétention de sécurité « complète » sans validation. |

**État après la séance du 2 novembre :** les premiers parcours **A et B sont vérifiés sur des tests ciblés**, pas sur un échange réseau avec Swing. Le prochain chantier est le **raccordement et la révocation de toutes les sessions HTTP**, associé à une future opération métier de désactivation autorisée. Les étapes C–E restent liées : aucune opération sensible ne sera ouverte sans droits, contrôle de structure et révocation appropriés.

## Avancement technique — 2 novembre 2026 : premier parcours HTTP

**Code vérifié dans le dépôt GitHub `main` ; tests locaux constatés via captures Eclipse.** Huit fichiers Java nouveaux ou modifiés ont été publiés pour ce chantier ; les résultats de tests sont décrits ci-dessus avec leurs limites.

### Réalisé sur un périmètre ciblé

- `HttpSecurityConfig` : `formLogin` natif Spring Security, gestionnaires de succès/échec sans redirection HTML, réponse `401` aux accès non authentifiés, déconnexion avec `204`, `denyAll()` maintenu hors routes explicitement autorisées.
- `CsrfController` : `GET /api/auth/csrf` pour obtenir un token CSRF avant la connexion. `AuthSessionController` : `GET /api/auth/me` protégé, renvoyant pour l'instant un JSON minimal contenant `userName`.
- Tests HTTP de connexion, CSRF, réutilisation de la session, déconnexion individuelle et refus d'une déconnexion sans CSRF ; tests d'intégration sur PostgreSQL isolé avec vrais comptes/credentials et deux connexions indépendantes.
- `UserAccount.equals(Object)` et `hashCode()` : identité définie par le `userID` immuable. `SessionRegistryIdentityTest` confirme que le registre **créé explicitement dans le test** retrouve deux objets Java représentant le même compte.

### Non réalisé / précautions avant usage réel

- **Registre des sessions HTTP :** le test unitaire n'enregistre pas les sessions servlet réelles dans un registre configuré par Spring Security. Il reste à configurer l'enregistrement, le retrait et la vérification effective d'expiration à chaque requête protégée.
- **Révocation globale :** pas encore de désactivation métier persistante, de parcours de changement/réinitialisation du mot de passe avec révocation, ni de preuve de refus des anciennes sessions sur plusieurs appareils.
- **Cohérence et sécurité :** une désactivation enregistrée ne doit pas laisser un accès autorisé si la révocation échoue ; prévoir simultanéité, transaction, éventuelles instances multiples et comptes modifiés pendant une requête. L'objet `UserAccount` en session peut devenir obsolète.
- **Autorisations :** `AuthorizationService` et ses règles `MANAGE_USERS` sont déjà testés indépendamment ; cela ne valide pas une opération complète de désactivation. Contrôles par structure, dernier administrateur et permissions HTTP restent à construire avant ouverture des API métier.
- **Intégration :** aucun client Swing connecté testé ; HTTPS, paramètres cookies, limitation des tentatives, MFA et validation globale avant publication restent à réaliser.

**Prochaine étape retenue :** brancher le suivi des sessions HTTP sur une identité stable (`userID`), puis concevoir et tester une désactivation de compte autorisée déclenchant la révocation de **toutes** ses sessions, avec garantie de refus même en cas d'échec technique. Pas de `boolean` de réactivation susceptible de rendre une ancienne session valable. Le mécanisme exact et ses protections doivent être validés par tests avant de le considérer comme opérationnel.

## Avancement technique — 3 novembre 2026 : révocation et multi-structures

**Périmètre de preuve :** captures Eclipse de tests ciblés réalisées localement. Le dépôt GitHub pouvait encore contenir uniquement les changements publiés le 2 novembre au moment de cette mise à jour ; les nouvelles réalisations du 3 novembre sont donc décrites comme **locales**, en attente de commit/push du code. Aucune opération technique n'a été lancée pour cette documentation.

### Sessions et gestion des comptes

- `HttpSecurityConfig` local : bean `SessionRegistryImpl` partagé, gestion des sessions concurrentes avec `maximumSessions(-1)` et stratégie répondant `401` aux sessions expirées.
- `SessionRevocationService` local : retrouve les sessions d'un `UserAccount` et appelle `expireNow()` sur chacune. Les tests démontrent une révocation sélective (Alice, pas Bob) et le refus des deux sessions HTTP d'Alice sur leur prochaine requête MockMvc.
- `UserAccountEntity.disable()`, `AccountStore.disableAccount(UUID)` et `AccountStore.findById(UUID)` avec adaptateur `JpaAccountStore` : opérations locales vérifiées par des tests PostgreSQL ciblés.
- `AccountManagementService` local : vérifie l'existence du demandeur, sa permission `MANAGE_USERS`, puis l'existence de la cible. Tests de refus ciblés au vert. **La méthode métier ne réalise pas encore la désactivation.**

**Sécurité restant à assurer :** droits tenant compte de la structure, dernier administrateur par structure, origine fiable de l'acteur depuis la session serveur, validation transactionnelle, gestion d'un échec de révocation et des accès simultanés, réévaluation des droits après changement, session partagée si plusieurs instances. Le registre en mémoire d'une seule instance ne suffit pas à ces garanties. Aucune API de désactivation ne doit être ouverte à ce stade.

### Structures indépendantes et persistance

- **Décision validée :** un `UserAccount` global peut être membre de plusieurs structures, avec des `Set<UserRole>` différents par structure. Une désactivation globale n'est pas la même opération que la suppression d'une appartenance.
- Modèles `OrganizationalStructure` (`structureID`, `structureName`) et `StructureMembership` (`userID`, `structureID`, rôles) ; tests unitaires réussis (5 et 6).
- Migration locale `V8__create_structures_and_memberships.sql` : tables `organizational_structures`, `structure_memberships` et `structure_membership_roles` ; clés primaires simples/composées, clés étrangères et contraintes ; **5/5 tests Testcontainers** sur PostgreSQL isolé.
- `OrganizationalStructureEntity`, `OrganizationalStructureJpaRepository` : conversions **3/3** et écriture/relecture PostgreSQL **1/1**.
- `StructureMembershipId` : `@Embeddable`, `Serializable`, deux UUID, égalité et hash cohérents ; tests **3/3**.
- `StructureMembershipEntity` : `@EmbeddedId`, `@ElementCollection`, jointure à deux colonnes vers la table des rôles, conversion `toDomain()` ; tests en mémoire **2/2**.

**Reprise précise :** créer `StructureMembershipJpaRepository` (clé `StructureMembershipId`), puis rédiger et exécuter un test JPA/PostgreSQL de plusieurs appartenances et rôles par structure, avec relecture après `flush()` / `clear()`. **Ne pas considérer ce mapping comme validé en base avant ce test.**

**À traiter ensuite, avant autorisation métier :** séparation entre le `employee_id` global actuel et les futures fiches salariées propres à chaque structure ; rattachement des unités et données métier aux structures ; transition explicite des anciens rôles globaux sans leur conférer des droits transversaux. Les permissions doivent être vérifiées côté serveur *et* les requêtes filtrées par structure.

**Récapitulatif des réussites ciblées du 3 novembre montrées dans la conversation :** 5 HTTP/PostgreSQL, 1 service de révocation, 3 persistance compte, 2 gestion compte, 5 modèle structure, 6 appartenance, 5 schéma V8, 3 conversion structure JPA, 1 persistance structure JPA, 3 clé composée et 2 conversion appartenance JPA, soit **36 tests réussis sur ces exécutions ciblées**. Ce total n'est **pas** une preuve d'exécution de toute la suite.

<a id="horizon-securite-2026-11-06"></a>
## Horizon de sécurité — exigences à anticiper (6 novembre 2026)

**Nature : décisions de conception et exigences futures, pas une attestation d'implémentation.** Objectif : faire évoluer le serveur Spring Boot et le client Swing sans reconstruire leurs fondations lorsque les protections seront ajoutées. À chaque chantier, distinguer ce qui est **présent dans le code**, **testé avec preuve**, **partiel** et **prévu** ; les validations historiques ci-dessus restent datées.

### Authentification, comptes et lutte contre les abus

- **MFA obligatoire pour `ADMIN` et `SUPER_ADMIN`** avant tout accès privilégié ; prévoir une **réauthentification forte récente** pour création, activation et transfert des comptes administrateurs. La MFA, le stockage des facteurs, leur enrôlement, récupération et révocation restent à concevoir et tester. Préférer des facteurs résistants au phishing lorsque réalisable ; ne pas confondre SMS d'activation et preuve MFA forte.
- **Cycle de vie sécurisé des comptes :** création/initialisation contrôlée, invitation, activation, mot de passe, récupération, suspension et désactivation. Ne pas contourner le lien métier avec les salariés sans décider explicitement le cas particulier du compte initial `SUPER_ADMIN`.
- **Anti-force brute :** limitation des tentatives côté serveur (y compris par compte et autres signaux adaptés), ralentissement progressif et, si nécessaire, blocage temporaire d'IP. Éviter un bannissement IP permanent ou exclusif : NAT, IP partagées et mandataires peuvent pénaliser des utilisateurs légitimes ; éviter aussi les mécanismes facilitant un déni de service sur les comptes.
- **Sessions :** expiration, rotation des identifiants de session, déconnexion, refus après désactivation ou changement des droits, révocation des sessions existantes et vérification sur chaque requête protégée. Prévoir la cohérence en présence de plusieurs instances serveur et éviter de faire confiance à des rôles figés dans la session.
- **HTTP :** cookies `Secure`/`HttpOnly` et `SameSite` approprié, protections CSRF, erreurs de connexion non révélatrices, autorisations vérifiées dans les services et aux frontières HTTP. Ne jamais considérer une restriction Swing comme protection serveur.

### Entreprises, structures et opérations privilégiées

- **Isolation multi-tenant :** chaque action, requête métier et donnée persistée doit être limitée à l'entreprise cliente et à la structure autorisées ; ne pas se contenter de masquer les éléments dans Swing. Les rôles globaux et ceux des `StructureMembership` conservent des responsabilités distinctes.
- **Administrateurs uniques :** un `SUPER_ADMIN` désigné par entreprise cliente ; un seul `ADMIN` par structure. La contrainte d'unicité SQL envisagée dans **V10** empêcherait *deux* `ADMIN`, mais **ne garantit pas à elle seule qu'il en existe toujours un**. La création d'une structure et son transfert d'administration doivent préserver cette règle ; ne pas modifier une migration Flyway déjà appliquée.
- **Transfert d'`ADMIN` :** exclusivement par le `SUPER_ADMIN` de l'entreprise propriétaire, après vérification de sa session/MFA récente et de l'éligibilité du successeur. Retirer le rôle de l'ancien puis attribuer celui du successeur **dans une transaction unique**, sous sérialisation adaptée des opérations concurrentes ; échec = retour à l'état précédent. L'ancien titulaire ne conserve pas le privilège `ADMIN`, mais peut conserver d'autres rôles autorisés. Prévoir audit (acteur, ancienne/nouvelle cible, structure, résultat) et réévaluation/révocation des sessions concernées.
- **Protection des opérations :** une unicité en base, des contrôles métier, des transactions et des tests de concurrence ont des rôles complémentaires ; aucun mécanisme seul ne constitue une garantie de sécurité globale.

### Communications, infrastructure et exploitation

- **HTTPS/TLS** pour tous les échanges réseau entre client Swing et serveur Spring Boot, avec vérification des certificats côté client. Le client n'accède pas directement à PostgreSQL ; le serveur demeure seul responsable des décisions de sécurité.
- **Clés SSH** pour l'**administration et le déploiement d'un serveur distant si l'infrastructure utilise SSH**, non pour la connexion des utilisateurs de Gestion+. Protéger les clés privées, privilégier phrase de passe, accès administratifs restreints, révocation/rotation et désactivation du mot de passe SSH si compatible avec la stratégie de récupération.
- **Secrets et exploitation :** identifiants PostgreSQL, facteurs MFA, jetons, sauvegardes et autres secrets hors dépôt ; privilèges minimaux, journalisation de sécurité, sauvegardes/restaurations vérifiées, mises à jour et durcissement de l'hébergement. Préparer l'architecture pour installation locale ou hébergement distant sans supposer que les garanties opérationnelles seront identiques.
- **Audit :** journaliser les opérations sensibles et refus pertinents, en évitant tout secret ; tenir compte des transactions annulées pour ne pas perdre silencieusement les événements d'échec nécessaires à la traçabilité.

### Continuité hors ligne : `ADMIN_EMERGENCY` (futur uniquement)

- Mode de secours réservé à **l'`ADMIN` de sa structure**, sur **poste préautorisé** ; il ne s'agit ni d'un nouveau rôle serveur ni d'une voie de contournement permanente des permissions.
- Prévoir authentification locale renforcée, données et journaux locaux protégés, autorisations limitées dans le temps, périmètre minimal, synchronisation contrôlée et résolution des conflits au retour en ligne.
- **Limite incontournable :** un poste déconnecté ne peut pas connaître immédiatement une révocation centrale. Définir explicitement la durée maximale de validité hors ligne et les conséquences d'un changement d'administrateur avant de déclarer le mécanisme sûr.

### Vérifications à prévoir au moment pertinent

- Tests unitaires et d'intégration des refus d'authentification, MFA, limitation des tentatives, sessions, permissions, isolation tenant/structure, transfert concurrent d'`ADMIN`, audit et révocation.
- Tests de sécurité des API et scénarios de déploiement (TLS, secrets, SSH, sauvegardes, restauration), puis tests propres au mode hors ligne lorsqu'il sera développé.
- **Organisation à préserver :** Swing = interface et échanges HTTPS ; Spring Boot = authentification, autorisations et règles métier ; PostgreSQL = stockage et contraintes ; infrastructure = SSH, TLS, secrets et protections d'exploitation. Concevoir chaque nouvelle étape en tenant compte de ces frontières, **sans implémenter maintenant des composants inutiles**.

**État au 6 novembre :** ces exigences forment un horizon de conception. Elles ne prouvent ni activation de SSH/TLS/MFA, ni limitation des tentatives, ni sécurité du mode `ADMIN_EMERGENCY`. La migration V10 évoquée pendant la séance est préparée localement par l'utilisatrice mais n'est pas réputée appliquée ni publiée par cette documentation.

## Faire vivre ce document

Après une séance, actualiser uniquement les blocs réellement concernés. Pour un résultat de test, noter date, commande ou outil, environnement anonymisé, nombres réussis/interrompus/échoués et limite de la preuve. Distinguer les décisions de conception des changements écrits et des validations exécutées.

Journal public :

| Date | Évolution documentée |
| --- | --- |
| 27 octobre 2026 | Première synthèse publique des sources actuelles et des preuves disponibles ; feuille de route ajoutée. Préparation documentaire uniquement, sans exécution du serveur ou des tests. |
| 29 octobre 2026 | Six décisions métier transversales ajoutées avec renvoi à la vision commune ; compte multi-structures et paramètres sensibles laissés ouverts. Documentation uniquement, sans nouvelle validation technique. |
| 2 novembre 2026 — conception | Décision Spring Security + sessions serveur + API JSON pour Swing ; exigences de révocation, MFA et sécurité HTTP ; ordre A–G pour la mise en œuvre. Documentation de conception initiale. |
| 2 novembre 2026 — réalisation et vérifications | Premier parcours HTTP Spring Security + CSRF + déconnexion, contrôleurs CSRF/session, égalité de compte par UUID ; captures locales : 9 tests MVC, 4 intégration PostgreSQL/Testcontainers et 1 registre d'identité réussis. **Révocation globale et intégration Swing non faites.** Mise à jour documentaire sur GitHub, sans test/build supplémentaire. |
| 3 novembre 2026 | Registre HTTP et service de révocation testés ; persistance de désactivation et refus métier partiels ; choix du compte unique multi-structures ; modèles, migration V8, entité/repository de structure, clé composée et entité d'appartenance en local. **36 réussites ciblées** sur les classes listées, mais pas de suite complète, pas de révocation automatique, pas d'autorisation inter-structures. Prochaine étape : repository et intégration JPA des appartenances. |
| 5 novembre 2026 — tests et documentation | Adaptation aux contrats locaux actuels : huit tests modifiés, deux ajoutés, 87 méthodes déclarées non compilées/non exécutées. Contrôles de périmètre, hiérarchie, audit et fixtures tenants ; problèmes de production signalés sans correction. Empreintes de src comparées, production inchangée ; aucune nouvelle réussite applicative ni publication. |
| 9 novembre 2026 — audit des rattachements | Instantanés des rôles attribués ou révoqués, protection contre le doublon concurrent, V7 à V12 appliquées localement et 356/356 tests réussis selon le lancement de l’utilisatrice. |