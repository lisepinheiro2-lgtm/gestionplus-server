---
summary: "23 novembre : V31 à V33, mise à niveau coordonnée, confirmations et verrous PostgreSQL ; cycle métier de succession à raccorder."
read_when:
  - "explorer les couches Java JPA PostgreSQL et les migrations sans lancer le serveur"
  - "préparer ultérieurement un lancement ou des tests expressément autorisés"
---

# Explorer et lancer le serveur localement

<a id="notifications-confirmations-succession-2026-11-23"></a>

## Repères techniques et mise à niveau — 23 novembre 2026

État relu sur **`aa9eff7`**, également HEAD de `main` distant. Voir [le suivi du 23 novembre](AVANCEMENT.md#notifications-confirmations-succession-2026-11-23) pour les six commits publiés et l’attribution des validations Codex : notifications **1 215/1 215** (24 nouveaux cas), confirmations **1 255/1 255** (40), puis offres **58/58 ciblés et 1 284/1 284 complets** (29). Aucun test ou lancement exécuté pendant l’actualisation documentaire.

| Domaine | Classes et migration à examiner | Contrat et précaution |
| --- | --- | --- |
| File de notifications | `AccountNotificationStore`, `PostgresqlAccountNotificationStore`, `AccountNotificationDelivery` ; V31 | Distinguer auteur, cible et destinataire ; adresse lue par `recipientUserID`. Enregistrement obligatoire dans la transaction métier, livraison SMTP ultérieure hors transaction. |
| Confirmations renforcées | `PrivilegedConfirmationService`, `StoredPrivilegedConfirmationVerifier`, `PostgresqlPrivilegedConfirmationProofStore` ; V32 | Empreinte SHA-256 seule, une preuve par compte/action, cinq minutes maximum ; contrôle après attente du verrou, DELETE conditionnel dans la transaction métier. Rollback conservant la preuve. |
| Offres de succession | `SuccessionOffer`, `SuccessionOfferStore`, `SuperAdminSuccessionService`, `PostgresqlSuccessionOfferStore` ; V33 | Offre PENDING de 24 heures créée par le service, sans changement des droits/sessions. Transaction métier MANDATORY et verrou partagé PostgreSQL d’organisation. |

**V31 exige une mise à niveau coordonnée** : `actor_id` devient `recipient_user_id`, car cette ancienne colonne contenait le destinataire. La nouvelle `actor_user_id` reste NULL pour les auteurs historiques inconnus ; conserver les destinations. Les types sont étendus et la structure peut être absente pour MFA. Ne pas supposer qu’une ancienne instance utilisant les anciens noms/types peut continuer à fonctionner avec ce schéma ; aucun déploiement mixte validé.

La perte du dernier accès produit une confirmation à l’administrateur et une information au titulaire, dans la transaction de désactivation ; aucun avis de désactivation si d’autres accès subsistent. Chaque transfert ADMIN produit deux avis, y compris lorsqu’il est effectué pendant une succession ; aucune notification générale d’offre ou de succession sans transfert ADMIN n’est déduite. La notification d’activation du facteur MFA est atomique avec cette activation, sans secret TOTP ni code de récupération.

La livraison reste conditionnée par `spring.mail.host` et `gestionplus.notifications.from`. Ordonnanceur et `MailDeliveryExecutor` réservent le travail, envoient hors transaction, puis finalisent ou reportent. **Trois tentatives maximum, puis abandon audité**, délai/réservation de cinq minutes inchangé : aucune réception garantie, reprise manuelle garantie ou absence de doublons SMTP. Un message accepté avant échec de finalisation peut être renvoyé.

La preuve V32 lie **compte, version de sécurité, action et digest du récapitulatif**, pas un identifiant individuel de session HTTP. OFFER et ACCEPTANCE restent indépendantes. `StoredPrivilegedConfirmationVerifier` est **bean Spring depuis `468edda`** ; les anciennes mentions d’absence de bean restent historiques. Limiteur dédié des tentatives et audit spécifique de l’émission/refus restent à compléter.

Pour V33, conserver l’ordre **structures triées → comptes triés → organisation → offre**. Le service prépare les verrous de structures et de comptes ; l’adaptateur verrouille l’organisation avant insertion et revérifie le titulaire sortant. La recherche d’anciennes offres ne verrouille pas leurs lignes. Sous READ_COMMITTED, ce verrou PostgreSQL commun sérialise les créations entre adaptateurs et JVM : une seule offre PENDING ou ACCEPTED non expirée par organisation. L’index partiel est non unique ; ne pas le présenter comme une contrainte unique temporelle. Expiration ou annulation autorisent une nouvelle offre, sans effacer l’historique ; rollback sans insertion résiduelle.

**Raccordement restant :** le dernier lot n’a pas réécrit les parcours métier d’annulation/finalisation. L’ancien `acceptSuccession()` ignore l’offre persistée et ne la marque pas COMPLETED. Toute adaptation devra préserver l’ordre des verrous pour éviter un interblocage. Ni ce cycle ni un parcours HTTP/Swing complet ne sont validés.

V33 testée par Codex sur bases jetables neuves et en passage V32 → V33, V1 à V32 inchangées dans ce dernier lot. La suite finale comprend les 14 scénarios de sessions à deux JVM existants et un nouveau scénario de concurrence du stockage entre deux JVM. Aucun e-mail/SMS réel ni déploiement validé. Aucun nouveau résultat client ; les limites MFA de reprise après contexte perdu/expiré et de codes irrécupérables après réponse perdue restent ouvertes.

**Historique conservé : les jalons antérieurs ci-dessous restent datés ; cette reprise décrit les évolutions du 23 novembre 2026.**


<a id="mfa-swing-2026-11-22"></a>

## Raccordement Swing réalisé, limites de reprise ouvertes — 22 novembre 2026

Le client **`ff68060`** raccorde désormais le parcours MFA restreint : réponse d’activation et preuve éventuelle, dialogue automatique, contexte/cookies/CSRF, clé masquée affichable, confirmation TOTP et présentation unique des dix codes. Transfert de responsabilité de la connexion entre contrôleurs, retour à la connexion normale avec nouveau TOTP, **sans connexion automatique ni accès métier**.

La mention « formulaire Swing à développer/raccorder » ci-dessous est historique. Voir le [suivi client courant](https://github.com/lisepinheiro2-lgtm/gestionplus-client/blob/main/docs/VALIDATION.md#mfa-swing-2026-11-22) pour **443/443 client dans chaque mode**, les quatre scénarios intégrés, les deux écrans en clair/sombre et les douze empreintes recoupées. Serveur de recette **`4a71440`**, identique au HEAD local relu ; la suite serveur complète **n’a pas été relancée**. Ses **1 191/1 191** appartiennent à la validation précédente, pas à ce raccordement.

Recette avec vrai Spring Boot et PostgreSQL/Redis jetables, mais **SMS simulés et HIBP remplacé**, aucun appel Free/OVHcloud ni SMS réel. Environnement arrêté et conteneurs supprimés selon le rapport. Aucun déploiement ou réception réelle validé.

**Ne pas considérer l’étape MFA entièrement close :** après perte/expiration du contexte, le serveur n’offre pas encore une reprise autonome avec une nouvelle preuve vérifiée. L’orientation vers l’administrateur n’ajoute aucun mécanisme administratif de reprise. Si la réponse de confirmation contenant les codes est perdue, le client ne peut pas les relire ; il présente un résultat incertain et ne répète pas automatiquement le TOTP. Ces limites restent à traiter ou à accepter explicitement. Aucun code serveur, test, configuration ou migration modifié par cette mission documentaire ; aucun test, build, serveur ou migration exécuté.

**Jalon antérieur au raccordement MFA Swing du 22 novembre : les dates, résultats et anciennes limites ci-dessous restent historiques ; lire la reprise ci-dessus pour l’état courant.**


<a id="mfa-restreint-2026-11-22"></a>

## Explorer l’enrôlement MFA restreint — 22 novembre 2026

État relu **`51407e3`**. Le serveur finalise le contexte restreint et ses opérations HTTP ; **le formulaire Swing reste à raccorder**, sans nouveau code ni nouvelle validation client dans cette mission. Le parcours de connexion normal conserve mot de passe et TOTP dans son POST, sans promotion automatique depuis l’enrôlement.

### Chaîne de lecture et échanges

Suivre `MfaEnrollmentRoutes` → `MfaEnrollmentController` → `AccountMfaService.openContext` / `beginEnrollment(EnrollmentPrincipal)` / `completeEnrollment(EnrollmentPrincipal, …)`. Puis lire `PostgresqlAccountMfaStore.consumeEnrollmentProofHash`, `EnrollmentPrincipal` et `CurrentAccountSessionFilter`.

1. Obtenir le CSRF anonyme, puis POST `/api/auth/mfa/enrollment/session` avec la preuve dans le JSON. Le serveur recherche son empreinte et la consomme une seule fois, après contrôles de compte ACTIVE, identité/version et expiration ; réponse **204**, rotation de session et invalidation du précédent contexte CSRF. Un UUID seul ne fournit aucune autorisation ; une session métier déjà ouverte ne peut pas être remplacée.
2. Relire GET `/api/auth/csrf` dans la session restreinte, puis POST `/api/auth/mfa/enrollment`. La preuve n’est pas redemandée ni consommée à nouveau. Le résultat comporte `enrollmentID`, `setupKey` et `expiresAt`. Begin répété conserve l’enrôlement encore valide, son secret et son échéance.
3. POST `/api/auth/mfa/enrollment/complete` avec `enrollmentID` et `code`, sous CSRF valide. Le facteur est confirmé, les codes de secours retournés et le contexte restreint fermé. Une connexion normale avec mot de passe et **nouveau TOTP** est ensuite nécessaire : le pas de confirmation est déjà consommé. POST `/logout` permet aussi d’annuler/quitter.

Le secret de configuration retourné à l’étape begin, la preuve et les codes de secours ne doivent pas être consignés dans les journaux, exemples ou captures partagées. Ils ne sont pas des attributs du contexte restreint Redis.

### Autorités, échéances et erreurs

`EnrollmentPrincipal` contient uniquement `userID`, `sessionVersion`, `expiresAt`. Échéance absolue **dix minutes après ouverture**, indépendante de l’activité : requêtes, lectures CSRF et begin ne la repoussent pas. Un contexte expiré/obsolète est invalidé lors de son contrôle. `ROLE_MFA_ENROLLMENT` reste distinct de `ROLE_USER` ; aucune route métier n’est ouverte par cette autorité.

Routes utilisables dans ce contexte : GET CSRF, POST begin, POST complete et POST logout. CSRF et refus par défaut demeurent. **Refus CSRF : 403 `FORBIDDEN` ; `CSRF_REJECTED` est une raison d’audit.** Quota dépassé : **429 avec `Retry-After`**. Contexte expiré/version obsolète : invalidation et 401. Redis indisponible : politique 503 sans repli local, selon le traitement de sécurité partagé existant.

### Redis et stockage PostgreSQL

`SessionValueSerializer` encode explicitement `enrollment-context` et préserve l’Instant d’expiration exact ; aucun mot de passe, preuve brute, secret TOTP ou autorité métier n’est stocké dans ce contexte. Les mélanges d’autorités et représentations incohérentes sont refusés. Sessions restreintes indexées par compte et incluses dans les révocations ; audit rattaché à leur identité.

`DistributedSessionConfiguration.securityClock` fournit l’horloge fondée sur Redis quand aucune horloge de remplacement n’est fournie ; les tests peuvent la remplacer. Le bean d’horloge ne transforme pas l’échéance absolue en durée glissante.

`RequestRateLimiter.acquireForAccount(scope, userID, limit, window)` dérive une clé de compte distincte du traitement IP de `acquire`. La confirmation utilise **cinq soumissions par compte en dix minutes**, partagées entre instances, conservées malgré les rollbacks PostgreSQL et non remises à zéro par begin. Le POST d’ouverture partage les **30 requêtes par IP sur quinze minutes** avec les routes publiques d’activation.

**V29** stocke les empreintes de preuves ; **V30** ajoute l’index unique `uq_mfa_enrollment_proof_hash` sur `proof_hash`. Consommation atomique avec verrou du compte et contrôles statut/version/expiration. Aucune migration existante modifiée ni aucune migration exécutée ici ; la validation antérieure utilise uniquement des bases jetables.

### Preuves et validations à distinguer

Lire `MfaEnrollmentHttpTest`, `EnrollmentSessionFilterTest`, `SessionValueSerializerTest`, `MfaEnrollmentProofPostgresqlIntegrationTest`, `RedisRequestRateLimiterIntegrationTest` et `MultiInstanceSessionIntegrationTest`. Les anciens tests MockMvc et leurs sessions servlet simulées restent distincts des **14 scénarios sur deux JVM avec Redis réel**.

Codex : **1 191/1 191 serveur**, sans échec/erreur/test ignoré, avec PostgreSQL/Redis jetables ; 25 empreintes recoupées avec le rapport. Les **1 157/1 157** restent historiques. Aucun appel Free/OVHcloud, SMS réel, nouveau test client ou déploiement. Voir [preuves, contrat et limites](AVANCEMENT.md#mfa-restreint-2026-11-22). Aucun test, build, serveur ou migration lancé pendant cette actualisation.

**Historique antérieur à la reprise MFA du 22 novembre : les résultats, décisions et anciennes limites ci-dessous gardent leur date et leur périmètre.**


<a id="activation-sms-swing-2026-11-21"></a>

## Développer et vérifier l’activation SMS — 21 novembre 2026

Référence relue `165d2e0`, client `ec08c42`. Commencer par `AccountActivationService` → `ActivationIdentityChallengeService` → `AccountInvitationStore.findByIDForUpdate`/`AccountStore.findByIdForUpdate` → `PostgresqlActivationIdentityChallengeStore`. La création exige l’appel hors transaction englobante, avec garde explicite ; l’envoi intervient après commit. Le téléphone, le jeton et l’expiration sont revalidés sous verrou avec un instant relu après attente.

### Sélectionner l’expéditeur OVHcloud, sans secret dans les sources

| Propriété externe | Usage |
| --- | --- |
| `gestionplus.sms.provider=ovh` | Active explicitement `OvhSmsConfiguration` et l’adaptateur SMS EU ; aucune dépendance ajoutée. |
| `gestionplus.sms.ovh.application-key` | Clé d’application externe. |
| `gestionplus.sms.ovh.application-secret` | Secret d’application externe. |
| `gestionplus.sms.ovh.consumer-key` | Clé consommateur externe. |
| `gestionplus.sms.ovh.service-name` | Nom du service SMS EU. |
| `gestionplus.sms.ovh.sender` | Expéditeur configuré et autorisé pour le compte fournisseur. |

Ces valeurs et les habilitations du compte sont à préparer hors dépôt ; aucune valeur réelle n’est fournie ici. L’assemblage refuse OVHcloud avec `gestionplus.sms.fictif=true` ou `gestionplus.sms.free-test.enabled=true`. **Free reste pour les essais locaux**. Cette exclusivité ne constitue pas une validation de production.

L’adaptateur utilise les endpoints EU fixes de temps et de jobs, des requêtes signées, des délais de connexion/échange de cinq secondes, une limite de réponse de 16 384 octets et aucune redirection. Une réponse ambiguë ou un échec ne déclenche aucune répétition automatique ; les erreurs publiques restent génériques. Aucun compte OVHcloud réel ni appel réel fournisseur n’a été utilisé dans les validations rapportées.

### V28 et récupération des envois

V28 ajoute `send_confirmed_at` et `send_recovery_at`, révoque les anciens défis encore ouverts et conserve l’historique. Sa préparation au déploiement doit tenir compte de cette révocation : les anciens codes ne restent pas utilisables. **Aucune migration n’est exécutée par cette mise à jour documentaire.**

`ActivationChallengeRecovery` est activé par défaut (`gestionplus.activation.challenge-recovery.enabled`), au démarrage et toutes les 30 secondes. Chaque passage traite au plus 100 envois non confirmés dont `send_recovery_at` est atteint, avec `FOR UPDATE SKIP LOCKED`. L’échéance est fixée à deux minutes après création. Défi non confirmé inutilisable, révocation sans réémission, tentatives conservées dans le quota. `confirmSend` signifie acceptation fournisseur, jamais réception sur téléphone. Si envoi et compensation échouent, l’échec initial reste levé avec l’échec compensant en exception supprimée.

### Prérequis réels des tests

`src/test/resources/application-test.yaml` utilise désormais **`jdbc:tc:postgresql:16-alpine:///gestionplus_test`** et `org.testcontainers.jdbc.ContainerDatabaseDriver`, avec identités fictives réservées au conteneur. Il **ne cible plus la base PostgreSQL locale** et ne réutilise plus ses variables d’identification. Pour les tests concernés : Docker accessible et image PostgreSQL disponible ou récupérable par Testcontainers. Les tests de sécurité partagée utilisent aussi Redis jetable et leur configuration isolée ; les anciens MockMvc restent distincts des douze scénarios HTTP à deux JVM.

Ne pas créer une base locale de test pour ce seul profil. Des remplacements externes peuvent modifier la destination effective : vérifier propriétés/profils et la configuration de chaque classe avant toute exécution autorisée. La base de développement et les rôles/propriétés de production gardent leur fonctionnement distinct. Tests purement unitaires et tests de l’adaptateur HTTP simulé ne prouvent aucune connexion à OVHcloud.

Les attentes des requêtes malformées sont désormais 400. Suites complètes antérieures de Codex : **1 143/1 143 serveur**, dont 12 scénarios à deux JVM, et **413/413 client sur classpath et en module**. Voir [attribution, références et limites](AVANCEMENT.md#activation-sms-swing-2026-11-21). Aucun test, build, serveur, migration ou appel fournisseur relancé pendant cette mission.

**Historique antérieur à cette reprise activation SMS/Swing : les dates, preuves et anciennes limites ci-dessous gardent leur contexte ; la reprise ci-dessus décrit l’état courant.**


<a id="multi-instance-2026-11-21"></a>

## Préparer plusieurs instances — 21 novembre 2026

État relu `f4c6931` : Spring Session Data Redis et les adaptateurs Redis de sécurité sont requis pour l’assemblage applicatif. `SessionLifetime(Clock)` et `SessionRegistryImpl` subsistent dans la configuration servlet **réservée aux tests** ; ce n’est pas une solution de secours de production.

### Paramètres externes, sans valeurs secrètes

| Paramètre externe / propriété | Contrat vérifié |
| --- | --- |
| `REDIS_HOST`, `REDIS_PORT` | Hôte requis, port 6379 par défaut. Toutes les instances d’un même déploiement doivent joindre le même service Redis et utiliser la même base logique effective. Ne pas réutiliser l’espace de noms d’un autre environnement. |
| `REDIS_USER`, `REDIS_PASSWORD` | Mot de passe externe requis ; utilisateur `default` uniquement comme valeur locale par défaut. Le profil `prod` exige `REDIS_USER` sans repli. Préparer les ACL Redis hors dépôt. |
| `REDIS_SSL_ENABLED` | Contrôle TLS Redis en configuration générale (false par défaut local) ; `prod` impose `spring.data.redis.ssl.enabled=true`. Préparer certificats et confiance TLS ; aucun TLS réel n’est validé par les tests jetables. |
| `GESTIONPLUS_CLUSTER_NAMESPACE` | Espace de noms commun aux instances d’un environnement, distinct entre environnements ; format `[a-z][a-z0-9-]{2,47}`. Spring Session utilise `gestionplus:<namespace>:sessions` ; les autres clés sont préfixées par scope. Ce paramètre ne configure pas Redis Cluster. |
| `GESTIONPLUS_RATE_LIMIT_KEY` | Clé HMAC commune, 32 à 64 octets encodés en Base64 ; propriété `gestionplus.cluster.rate-limit-key`. Aucune valeur d’exemple secrète à publier. |
| `gestionplus.mfa.key`, `gestionplus.activation.delivery-key`, `gestionplus.activation.challenge-key` | Clés externes communes au déploiement. L’empreinte stockée dans Redis au premier démarrage est comparée par les suivants, avec la clé HMAC commune. Incohérence ou Redis indisponible bloque le démarrage ; rotation/restauration doivent être coordonnées, sans effacer l’empreinte pour contourner le contrôle. |
| `gestionplus.audit.fallback.key` / `gestionplus.audit.fallback.directory` | Clé externe du journal chiffré et **répertoire durable propre à chaque instance**. Le contrôle d’empreinte multi-instance ci-dessus ne comprend pas cette clé d’audit. Prévoir collecte/reprise et sauvegarde des journaux de chaque instance ; Redis ne partage pas ces fichiers. |
| Profil `reverse-proxy` / `GESTIONPLUS_TRUSTED_PROXIES` | Adresses IP ou réseaux CIDR explicites séparés par virgules. Profil : stratégie native Tomcat, `x-forwarded-for` et `x-forwarded-proto`. Sans ce profil, les en-têtes transférés ne sont pas interprétés. Aucun proxy de confiance universel ou implicite. |

Vérifier la configuration Redis effective et ses remplacements externes, notamment `SPRING_DATA_REDIS_URL` : la seule lecture de `REDIS_HOST` ne prouve pas la destination utilisée. Les tests isolés fournissent leur propre fabrique de connexion pour éviter cette dépendance à la configuration du poste.

Les délais de connexion/commande Redis sont configurés à 2 secondes. `ConfigureRedisAction.NO_OP` signifie que l’application ne configure pas le serveur Redis : préparer ses permissions et notifications nécessaires au repository indexé (les conteneurs de test utilisent `notify-keyspace-events=Egx`). Protéger Redis et PostgreSQL par le réseau ; le proxy doit maîtriser les en-têtes transmis et les serveurs applicatifs ne doivent pas être accessibles par un chemin contournant cette confiance. Le proxy réel, les règles réseau et l’infrastructure de production restent à configurer.

Le profil `prod` conserve HTTPS serveur, cookie Secure et exigence HTTPS, en plus de TLS Redis ; le profil proxy ne constitue pas à lui seul une terminaison TLS validée. Les sources ne suffisent pas à prouver certificats, ACL, sauvegardes, reprise ou haute disponibilité.

### Contrats à lire et validations disponibles

Lire `DistributedSessionConfiguration`, `DistributedSessionRegistry`, `SessionLifetime`, `RedisSessionActivityStore` et `session-activity.lua` pour sessions/échéances ; `SessionValueSerializer` pour les types JSON autorisés ; `RedisLoginAttemptLimiter`/`login-attempts.lua` pour la limitation partagée.

`RequestRateLimiter.acquire(scope, address, limit, window)` accepte un scope `[a-z][a-z0-9-]{1,63}`, une limite 1–10 000 et une fenêtre de 1 seconde à 1 jour ; résultat `Decision(allowed, retryAfter)`. Il est destiné au filtre public développé séparément par Lise. **un squelette local de `PublicInvitationRateLimitFilter` est en cours chez Lise, hors du commit et des validations du lot** : contrat/adaptateur ne signifie pas filtrage HTTP déjà raccordé.

Les 291/291 tests ciblés antérieurs, les dix scénarios à deux JVM, l’isolation Redis et la distinction MockMvc/Redis réel sont décrits dans l’[avancement](AVANCEMENT.md#multi-instance-2026-11-21). Le benchmark HTTP inclut désormais un Redis jetable mais n’a été que compilé ; ses réserves sont dans le [protocole](benchmarks/authentication/README.md#multi-instance-2026-11-21). Aucune commande exécutée pendant cette actualisation.


**Jalon antérieur au lot multi-instance du 21 novembre : lire la reprise ci-dessus pour l’état courant et les décisions remplacées.**

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

**Contrat courant (21 novembre) :** `createChallenge(invitationID, expectedTokenDigest)` refuse explicitement une transaction englobante. Compte/invitation, téléphone, jeton et instant sont revalidés sous verrou ; envoi après commit. Voir la reprise activation ci-dessus.

**Contrat historique remplacé, conservé pour mémoire :**

> **Contrainte obligatoire pour l’appelant :** invoquer `createChallenge(invitationID)` **sans transaction Spring déjà active**, donc hors d’une méthode exécutée sous `@Transactional` et hors d’un `TransactionTemplate` actif. Le service conserve la propagation `REQUIRED` et ne contrôle pas automatiquement cette précondition ; un appel transactionnel pourrait envoyer avant le commit. Voir le [contrat d’appel détaillé](AVANCEMENT.md#contrat-appel-creation-defis-sms). Le compte est relu sous verrou et le délai/quota sont revérifiés avant le remplacement. L’invitation n’est pas relue à cet endroit et le téléphone utilisé reste celui lu avant verrou ; voir la [fenêtre résiduelle documentée](AVANCEMENT.md#telephone-avant-verrou-defis-sms).

### Simulation SMS locale et tests HTTP — 19 novembre 2026

Dans Eclipse, activer le simulateur par l’argument VM `-Dgestionplus.sms.fictif=true`, uniquement dans la configuration locale choisie. Il journalise le téléphone et le code : employer des données fictives. Le flag n’est pas activé par défaut dans les fichiers versionnés et aucune garde ne rejette automatiquement son utilisation avec `prod`. Avant un usage réel, désactiver le fictif et vérifier la sélection du fournisseur SMS réel.

Pour une vérification de démarrage, préparer une base PostgreSQL isolée et diriger `spring.datasource.url` vers cette base dans les arguments du programme. Flyway utilise alors la même source de données si aucune connexion de migration distincte n’est configurée. Avec Spring Boot 4.1.2, fournir seulement `spring.flyway.url` crée une connexion séparée sans reprendre automatiquement le mot de passe de l’application ; des identifiants Flyway explicites sont nécessaires pour ce mode. La séparation des identités du profil `prod` reste inchangée.

Les deux contrôles de démarrage ont été réalisés par Lise le 19 novembre sur une base locale isolée : démarrage réussi avec le flag, puis échec identifiant précisément le bean `ActivationMessageSender` manquant sans flag. Ces contrôles ne prouvent pas l’envoi d’un défi.

Depuis `4c630ee`, lire `CsrfHttpTest` pour les accès SMS et CSRF avec les vrais filtres, puis `SessionLifecycleHttpTest` pour les sessions. Ce sont des tests `@WebMvcTest`, avec services métier simulés, sans PostgreSQL. Les jetons proviennent du vrai endpoint CSRF ; la durée de session utilise le vrai `SessionLifetime` et une horloge pilotée. Le bilan global de **900/900** est une validation locale rapportée par Lise. [Détail des preuves](AVANCEMENT.md#securite-http-sessions-2026-11-19)

## Prérequis d'une exécution locale

- **JDK 25**, également sélectionné dans l'éditeur ou le terminal de lancement.
- **Maven Wrapper** fourni : il demande Maven **3.10.0** selon sa configuration. Son téléchargement et celui des dépendances nécessitent un accès aux dépôts de paquets. La disponibilité de cette distribution et la résolution complète n'ont pas été revérifiées ici.
- **PostgreSQL local** : la version de travail annoncée est 18 ; aucune matrice de compatibilité avec d'autres versions n'est établie.
- Une base de développement avec données fictives, séparée des environnements jetables. Le profil de test et les tests d’intégration concernés utilisent PostgreSQL Testcontainers ; Docker accessible est requis pour eux, avec Redis jetable pour la sécurité partagée.

Le dépôt ne fournit ni compte initial réel ni données serveur préchargées de production. Le parcours d’activation SMS/Swing et l’adaptateur OVHcloud sont présents ; la réception réelle et l’exploitation restent à valider.

## Préparer la base et l'environnement

Préparer séparément la base de développement `gestionplus_dev` avec un compte dédié. Le profil `test` démarre désormais PostgreSQL jetable via Testcontainers : ne pas créer de base locale `gestionplus_test` pour ce profil. Les anciennes instructions de test sur une base locale sont remplacées au 21 novembre par ce fonctionnement jetable.

| Paramètre | Configuration livrée |
| --- | --- |
| Développement | `jdbc:postgresql://localhost:5432/gestionplus_dev` |
| Tests Spring portant le profil `test` | `jdbc:tc:postgresql:16-alpine:///gestionplus_test` ; driver Testcontainers et identités fictives du conteneur. |
| Identifiant de connexion | Variable d'environnement `DB_USER` |
| Mot de passe | Variable d'environnement `DB_PASSWORD` |
| Schéma | Flyway activé ; validation Hibernate avec `ddl-auto: validate`. |

Définir `DB_USER` et `DB_PASSWORD` dans l'environnement du processus lancé, par exemple via la configuration d'exécution privée de l'éditeur. Ne pas mettre leurs valeurs dans le code, le pom, les commandes partagées ou un fichier versionné. Ne pas envoyer une capture de la configuration contenant ces valeurs.

Un fichier `.env` est exclu de Git, mais **aucun chargement automatique de ce fichier n'est configuré dans le projet**. Son existence ne suffit donc pas à fournir les variables.

Vérifier l’URL effective et les profils : `SPRING_DATASOURCE_URL` peut remplacer la destination attendue. Depuis ce lot, le profil `test` utilise le driver Testcontainers et des identités fictives propres au conteneur ; l’ancien partage des variables de développement n’est plus sa configuration livrée.

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

Après vérification de Docker, de la configuration jetable de la classe et des variables effectives, lors d’une exécution autorisée :

```powershell
.\mvnw.cmd test
```

Équivalent Linux/macOS : `sh ./mvnw test`. Certains tests Spring utilisent le profil `test` et son PostgreSQL Testcontainers jetable ; d’autres, notamment les classes d’invitation, définissent une URL Testcontainers et démarrent leur propre PostgreSQL jetable. Lire la configuration de la classe avant tout lancement : le profil `test` seul ne garantit pas l’isolation. Les tests unitaires utilisent aussi des composants en mémoire ou simulés. Les programmes de benchmark ont leur propre environnement isolé et ne constituent pas la suite JUnit.

Consulter `target/surefire-reports/` si le lancement Maven produit des rapports. Relever les réussites, échecs et interruptions : un code de sortie nul ou une classe présente ne prouve pas que tous les scénarios ont été exécutés. L'état effectivement connu reste décrit dans [AVANCEMENT.md](AVANCEMENT.md).

Les [benchmarks manuels d’authentification](benchmarks/authentication/README.md) disposent d’un protocole distinct, avec serveur et base jetables, comptes fictifs et preuves archivées. Ils ne mesurent pas le TLS, le WAN ni la charge concurrente.

## Limites et suite des validations

Jalon historique rapporté par Lise : **900/900 tests réussis dans Eclipse le 19 novembre 2026 sur `4c630ee`**. Aucune exécution de l’assistant. [Chaîne HTTP, sessions et limites](AVANCEMENT.md#securite-http-sessions-2026-11-19)

Au raccordement, tenir compte du contrat hors transaction et des lectures de l’invitation et du téléphone avant verrou. Les réserves portent aussi sur le rattachement des audits SMS à l’entreprise, les instants utilisés après attente des verrous et le double échec de l’envoi et de sa compensation. [Réserves du 18 novembre](AVANCEMENT.md#reserves-sms-2026-11-18)

Aucune correction Java, configuration applicative ou migration n'est nécessaire pour simplement consulter ou publier les sources du chantier. Une éventuelle correction technique fait l'objet d'une intervention distincte.

Référence : [arguments du plugin Maven Spring Boot](https://docs.spring.io/spring-boot/maven-plugin/run.html).
