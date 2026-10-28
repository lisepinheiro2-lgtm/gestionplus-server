---
summary: "État public du serveur relu le 27 octobre 2026 et feuille de route avec niveaux de preuve."
read_when:
  - "reprendre les comptes authentification rôles permissions sessions et audit"
  - "distinguer code présent tests écrits résultats disponibles et fonctions prévues"
---

# Avancement et feuille de route

État relu le **27 octobre 2026 (Europe/Paris)** à partir des sources actuelles et des preuves déjà disponibles. Aucun test, build, serveur ou migration n'a été exécuté lors de cette préparation documentaire.

## Comment lire les statuts

- **Implémenté** : code présent et examiné ; pas de promesse de fonctionnement global.
- **Vérifié sur un périmètre précis** : résultat d'exécution disponible, avec sa date et ses limites.
- **En cours** : pièces présentes, assemblage ou validation encore incomplets.
- **Prévu** : objectif de conception, sans parcours opérationnel annoncé.

## État des blocs

Les chemins ci-dessous sont relatifs à `src/main/java/fr/gestionplus/server/`.

| Bloc | Présent dans les sources | État et limite |
| --- | --- | --- |
| Comptes et credentials | `auth/UserAccount`, `UserAccountStatus`, `UserCredential` ; UUID distinct du salarié ; états en attente, actif et désactivé. | Implémenté au niveau du modèle. Invitation et activation complète à construire. |
| Authentification métier | `auth/LoginService`, contrats Lookup et adaptateurs JPA. Refus d'un compte absent ou non actif, d'un credential absent ou mal rattaché, et d'un secret incorrect. | Implémenté ; tests unitaires et cinq scénarios d'intégration écrits. Pas de parcours HTTP de connexion utilisable. |
| Mots de passe | `PasswordPolicy` : 12 à 128 caractères, espaces significatifs ; contrôle de compromission ; `CredentialService` et encodeur Argon2. | Implémenté, validation globale à poursuivre. Créer un credential ne le persiste pas à lui seul. |
| Vérification de compromission | `integration/PwnedPasswordsChecker` interroge le service Pwned Passwords avec un préfixe d'empreinte ; sept tests avec client HTTP simulé. | Code et tests présents ; comportement réel, réponses multiples et cas limites à valider. Cette empreinte de recherche ne remplace pas Argon2 pour le stockage. |
| Spring Security | `GestionAuthenticationProvider` et `HttpSecurityConfig`. | Raccordement partiel. `denyAll()` refuse toutes les requêtes ; aucune route applicative exposée trouvée. Les autorités Spring ne sont pas encore alimentées à partir des rôles. |
| Rôles et permissions | `RolePermissionPolicy` et `AuthorizationService` ; comptes non actifs refusés ; cumul de rôles. | Première politique implémentée. Les rôles incluent `R_H`, mais sa politique dédiée et les contrôles hiérarchiques restent à définir et appliquer. |
| Persistance des comptes | Entités, repositories, Lookups et `persistence/JpaAccountStore`. | Contrôles du rattachement à un salarié présents ; parcours transactionnel complet et cas concurrents à valider. La création directe dans `AccountProvisioningService` est volontairement désactivée. |
| Salariés et unités | Modèles `Employee` et `OrganizationalUnit`, stores, entités JPA et référence d'un salarié vers une unité. | Socle implémenté ; trois tests de conversion vérifiés sur le périmètre décrit ci-dessous. Persistance complète à valider. |
| Schéma | Six migrations SQL : comptes/credentials/rôles, états de compte, salariés, rattachement compte-salarié, unités, rattachement salarié-unité. | Scripts présents ; pas de nouvelle preuve d'exécution complète V1–V6 pendant cette revue. |
| Audit | Événements, actions, cibles, `AuditService`, contrat `AuditStore` et contexte Spring. | En cours. Aucun stockage concret d'audit trouvé ; raccordement aux opérations et protection des traces à réaliser. Les noms d'actions ne prouvent pas l'existence des fonctionnalités correspondantes. |
| Client Swing connecté | Projet client distinct, actuellement autonome en CSV. | Intégration prévue ; aucun échange avec ce serveur dans le démarrage client examiné. |
| Sessions, invitations, récupération, MFA et `ADMIN_EMERGENCY` | Objectifs de conception. | Parcours complets à construire et à vérifier ; aucune disponibilité annoncée. |

La configuration actuelle contient bien `spring.jpa.hibernate.ddl-auto: validate` et un profil de test PostgreSQL. Les anciens constats sur une indentation incorrecte ou l'absence de profil ne décrivent donc plus les fichiers actuels. Cela ne prouve pas leur bon fonctionnement à l'exécution.

## Tests : sources et résultats sont deux informations différentes

Inventaire actuel : **15 classes et 59 méthodes annotées `@Test`**. Ce nombre décrit les sources, pas le nombre de tests réussis.

| Périmètre | Résultat disponible | Ce que cela démontre |
| --- | --- | --- |
| `OrganizationalUnitEntityTest` et `EmployeeEntityTest` | Rapport local du 27 octobre 2026 : **3 réussites** lors d'un lancement ciblé JUnit fourni par l'éditeur. | Conversions en mémoire, conservation des champs et des identifiants dans ces cas. |
| `EmployeeOrganizationJpaPersistenceTest` | Dix scénarios écrits ; exécution interrompue avant leurs assertions de persistance lors de la même campagne. | **Persistance non validée par cette campagne.** Ces dix cas ne sont pas comptés comme réussis. |
| Authentification, autorisations, credentials, repositories et vérification de compromission | Tests présents ; pas de rapport actuel de réussite globale retenu ici. | Couverture à examiner et exécutions à confirmer. Les succès historiques isolés ne valent pas validation des sources actuelles. |
| Cycle Maven complet | Dernière tentative ciblée documentée : arrêt avant les tests, dépendance de lancement absente du cache hors ligne. | Aucun résultat global vert revendiqué. |
| HTTP, sessions, révocation, permissions de portée et sécurité de bout en bout | Aucune preuve complète disponible dans cette revue. | Fonctionnalités à développer ou à valider selon leur état. |

Le rapport ciblé est conservé localement ; les fichiers générés et journaux bruts sont exclus du dépôt. Les résultats ci-dessus sont rapportés à partir de cette preuve existante, sans nouvelle exécution et sans prétendre à une reproduction publique complète.

## Feuille de route

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

## Faire vivre ce document

Après une séance, actualiser uniquement les blocs réellement concernés. Pour un résultat de test, noter date, commande ou outil, environnement anonymisé, nombres réussis/interrompus/échoués et limite de la preuve. Distinguer les décisions de conception des changements écrits et des validations exécutées.

Journal public :

| Date | Évolution documentée |
| --- | --- |
| 27 octobre 2026 | Première synthèse publique des sources actuelles et des preuves disponibles ; feuille de route ajoutée. Préparation documentaire uniquement, sans exécution du serveur ou des tests. |