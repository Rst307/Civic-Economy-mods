# Civic Economy implementation status

Last updated: 2026-07-14 (Asia/Shanghai)

This file distinguishes unit fixtures, artifact/source inspection, compilation, GameTest, client runtime, and dedicated-server runtime evidence. One form of evidence must not be presented as another.

## Completed

- Integrated the isolated single-player debug-world specification from PR #1 into `agent/v0.1-playable`.
- Created the Java 21 / Minecraft 1.21.1 / NeoForge 21.1.197 Gradle skeleton and wrapper.
- Pinned Lightman's Currency `1.21-2.3.0.5`, FTB Library `2101.1.32`, FTB Teams `2101.1.10`, FTB Chunks `2101.1.20`, and optional Create artifact `1.21.1-6.0.6`.
- Added a pure compatibility module with exact-version and probe-class checks.
- Required LC/FTB absence, version drift, or class drift fails closed.
- Create absence leaves the fiscal core enabled; Create version or class drift disables production scoring without blocking startup.
- Added a NeoForge runtime detector and enforced the compatibility report during Civic mod construction.
- Added a real SQLite persistence module with WAL, foreign keys, busy timeout, schema versioning, and persisted world/dependency identity.
- SQLite open fails closed for a foreign world UUID or an unknown schema version.
- Bundled exact `sqlite-jdbc` `3.50.3.0` in the NeoForge JAR using jar-in-jar metadata.
- Added checked, nonnegative LC-minor-unit `MoneyAmount` values.
- Added SQLite schema v2 durable Reservations with service-scoped `requestId` idempotency, payload-conflict rejection, available-balance calculation, and per-account concurrency control.
- Added SQLite schema v3 payment transactions with durable `PREPARED`, `EXTERNAL_APPLIED`, `CIVIC_COMMITTED`, `COMPENSATING`, and `COMPENSATED` states.
- Added restart recovery for crashes after an external payment and for the ambiguous window between external application and Civic recording the result; retries retain the same transaction UUID.
- Added a version-locked LC player-bank payment adapter using real `BankAPI` withdraw/deposit methods and LC main-chain minor units.
- Added a production `AccountBalances` adapter that reads exact LC main-chain minor units for existing `player:<UUID>` bank accounts and delegates registered Civic fiscal accounts to their LC-backed adapter.
- Player balance lookup fails closed for malformed IDs, unknown LC player accounts, and unregistered non-player accounts; it checks `BankDataCache.hasAccount` before `getAccount` so a balance read cannot implicitly create an account.
- Added a required `BankDataCache` Mixin that persists applied Civic transaction UUIDs in the same LC `SavedData` snapshot as player bank balances.
- Added a real NeoForge GameTest proving one LC player-bank transfer, replay idempotency, exact source/recipient balances, and marker save/reload.
- Added world-scoped Civic `SavedData` storage for LC-backed National Treasury and Organization Fiscal Account objects keyed by `AccountId`.
- Registered a Civic LC `BankReferenceType` and `BankAccountSource`; every native LC player access, salary target, salary edit, and reference-persistence path is denied.
- Civic fiscal accounts ignore LC interest and salary ticking so LC cannot create unaudited fiscal money through those hooks.
- Generalized the LC payment adapter to transfer among player accounts and Civic fiscal accounts while retaining the LC transaction marker.
- Added a real LC + real SQLite GameTest that funds a National Treasury, creates a Reservation, injects the ambiguous external-payment crash window, recovers through `PaymentCoordinator`, and pays a player exactly once.
- Explicitly loads the pinned SQLite JDBC driver and places it on NeoForge development-run additional runtime classpaths as well as in the built jar-in-jar artifact.
- Added server-only FTB Teams and FTB Chunks adapters compiled against the exact pinned artifacts.
- `FtbTeamsAdapter` returns immutable facts for valid FTB Teams, including stable team UUID, effective team UUID, owner, members, and team type; it never treats an FTB Team as a Nation.
- `FtbChunksAdapter` returns exact current claim ownership, dimension, chunk position, claim time, requested force-load state, and actual force-load state.
- Expanded runtime class probes for every FTB API class used by the adapters and added real GameTests for one server-team query and one claim/query/unclaim cycle.
- Raised the SQLite schema to v4 with a persistent Nation registry keyed by a Civic-generated stable `NationId` and a unique external FTB Team UUID binding.
- Added idempotent Nation registration with service-scoped `requestId`, payload-conflict rejection, unknown-team fail-closed behavior, and strict one-to-one FTB Team binding.
- Added the stable `NationProvider` interface and a temporary FTB Teams-backed implementation that exposes current head/citizen facts without exposing the FTB Team UUID as the Nation identity.
- Ordinary unregistered FTB Teams remain non-Nations. If a registered Nation's bound FTB Team becomes unavailable, provider lookup fails closed without deleting the stable Nation or its history.
- Added an exact FTB-to-domain team-directory adapter plus a real FTB Teams + SQLite GameTest proving registered-team resolution and `NationId != FTB Team UUID`.
- Added a real schema-v3-through-current SQLite migration test that preserves the world/dependency identity and produces usable Nation and Citizenship registries.
- Raised the SQLite schema to v5 with appendable Citizenship periods, a partial unique index enforcing one active Nation per player, and durable join/leave request identities.
- Added a persistent Citizenship registry with server-clock timestamps, service-scoped join and leave replay, changed-payload rejection, unknown-Nation rejection, active-Citizenship conflicts, nation-specific stale-leave protection, ordered history, and a configurable transfer cooldown.
- Leaving Citizenship immediately closes the active period, so subsequent permission and population modules can fail closed without waiting for historical cleanup. A player can join another Nation only after the configured cooldown has fully elapsed.
- Raised the SQLite schema to v6 with idempotent completed online-time intervals indexed by player and time.
- Added a conservative online-time ledger: completed observed intervals replay exactly once, changed payloads conflict, overlapping intervals are rejected to prevent double counting, adjacent intervals are valid, and rolling queries clip at the requested window boundaries.
- Added Effective Citizen contribution calculation that intersects online-time intervals with actual Citizenship periods, applies the configured observation window, prorates transfers by exact affiliation time, and caps each citizen at `1` after the configured full-contribution duration.
- Added a world-scoped UUID in Minecraft `SavedData`; a newly generated identity is synchronously persisted before the runtime SQLite database opens, and subsequent starts require the database identity to match it.
- Added the authoritative server runtime composition at `<world>/civiceconomy/civic.sqlite3`, using the loaded Civic/LC/FTB versions in the database identity.
- Added a server-only online-session accumulator and NeoForge lifecycle adapter. Login starts an in-memory session, one-minute checkpoints and logout produce completed intervals, duplicate logins do not reset time, and clock regressions conservatively produce no interval.
- Completed intervals are queued to one background SQLite writer. Regular Minecraft ticks never execute SQLite; graceful shutdown drains the queue with a bounded timeout and fails closed on a writer error.
- Raised the SQLite schema to v7 with immutable Reservation release records, then to v8 with exact settled and remaining amounts on each Reservation.
- Added durable idempotent Reservation release. Release frees the logical hold without moving LC money, records a required reason and timestamp, survives restart, and rejects changed replay payloads.
- Release fails closed for settled/released/unknown Reservations and for any Reservation with a `PREPARED` or `EXTERNAL_APPLIED` payment, preventing cancellation of money that may already have moved externally.
- Added partial settlement. Each committed payment reduces only the remaining hold, the Reservation stays active until fully consumed, over-settlement is rejected, and a later release frees only the unpaid remainder.
- Partial crash recovery reuses the existing transaction UUID and commits only the recovered payment amount; the unpaid Reservation remainder survives restart.
- Raised the SQLite schema to v9 and added `PAYMENT` / `REFUND` transaction kinds with durable parent linkage, cumulative refunded amount, and an incomplete-refund uniqueness guard.
- Added idempotent full and partial refunds for committed payments. Refunds reverse source/recipient accounts through the same external transaction UUID marker and reject amounts above the remaining refundable value.
- Refund recovery covers both ambiguous external application and recorded `EXTERNAL_APPLIED` restart windows; replay does not repeat the economic effect.
- Added online SQLite backups through Xerial's native backup API, so an open WAL database can produce a consistent point-in-time snapshot without copying live database files directly.
- Added validated restore to a new database path. Restore fails closed before publication unless the backup has the exact current schema (currently v24) and exactly matches the expected world UUID plus Civic, LC, FTB Teams, and FTB Chunks versions.
- Restore refuses to overwrite an existing destination and publishes through a temporary sibling file, using an atomic move when the filesystem supports it.
- Raised the SQLite schema to v10 with durable payment-compensation requests and immutable recovery-audit records.
- Added controlled compensation for a `EXTERNAL_APPLIED` payment or refund that must not complete normally. The transaction enters `COMPENSATING` before the reverse transfer and reaches `COMPENSATED` only after the reverse is confirmed.
- Compensation persists a dedicated reverse-transfer UUID before calling LC, so a crash after the external reverse but before Civic records completion can replay without a second economic effect.
- A compensated payment does not consume its Reservation. The hold remains active for release or a later valid settlement, while a `COMPENSATING` transaction blocks unsafe release and overlapping payment/refund work.
- Recovery advancement and its audit record commit in the same SQLite transaction. Ambiguous `PREPARED` recovery records external confirmation, and recovery from `EXTERNAL_APPLIED` records the Civic commit.
- Compensation request identity, reason, start, and completion remain queryable through the `PaymentCoordinator` interface across database reopen.
- Raised the SQLite schema to v11 with durable Escrow and Escrow-expiry records linked one-to-one to their underlying Reservations.
- Added atomic reserved-Escrow creation through `FiscalLedger`: the Escrow and Reservation either both commit or neither does, service/request replay returns the original object, and changed payloads conflict instead of adding another hold.
- Each Escrow records its source account, amount, settled amount, remaining amount, purpose, external object ID, expiry, and explicit lifecycle state.
- Partial and final payment commits advance the linked Escrow to `PARTIALLY_SETTLED` and `SETTLED` in the same SQLite transaction that advances the Reservation.
- Reservation release advances the linked Escrow to `RELEASED` in the same transaction and frees only the unpaid remainder.
- Idempotent server-driven expiry refuses execution before the deadline, blocks while payment is ambiguous or compensating, and atomically records expiry, releases the remaining Reservation, and advances the Escrow to `EXPIRED`.
- Raised the SQLite schema to v12 with durable Budget drafts and a separate idempotent approval identity.
- Budget draft creation records source account, amount, budget code, purpose, and expiry without reserving LC funds. Draft replay survives reopen and changed payloads conflict.
- Budget approval checks current LC-backed available balance and atomically creates the Reservation and Escrow, links them to the Budget, and advances `DRAFT -> APPROVED`; approval replay cannot create a second hold.
- Payment settlement advances the linked Budget in the same SQLite transaction: `APPROVED -> PARTIALLY_SPENT -> SPENT`, with exact settled and remaining amounts.
- Reservation release and Escrow expiry advance the linked Budget to `RELEASED` or `EXPIRED` in the same transaction while preserving already-spent amount and freeing only the remainder.
- Raised the SQLite schema to v13 with durable manual Fiscal Bills for explicit `TAX`, `FEE`, and `DUES` receivables.
- Bill issuance records payer, beneficiary, amount, kind, purpose, and due date without moving or reserving funds; service/request replay survives reopen and changed payloads conflict.
- Bill funding checks payer available balance and atomically creates a Reservation plus a recipient-bound Escrow. The required beneficiary is visible on the Escrow interface and funding replay cannot create a second hold.
- Payment preparation fails closed before external application when a recipient-bound Reservation targets any account other than the bill beneficiary.
- Correct partial and final payments advance Bill state in the same SQLite transaction as Reservation/Escrow settlement: `RESERVED -> PARTIALLY_PAID -> PAID`.
- Reservation release cancels a funded Bill while preserving already-paid amount, and Escrow due-date expiry advances the Bill to `EXPIRED` while releasing only the unpaid remainder.
- Raised the SQLite schema to v14 with immutable paired general-ledger entries for committed formal payments and refunds.
- Each committed transaction creates one source `OUTFLOW` and one recipient `INFLOW`, with transaction UUID, counterparty, exact LC minor-unit amount, payment/refund kind, and record time.
- Ledger rows commit in the same SQLite transaction as Reservation, Escrow, Budget, Bill, refund-total, and payment-state changes. A ledger insert failure therefore rolls back the Civic commit instead of leaving an unaudited formal flow.
- Payment and refund replay return existing transactions without adding duplicate ledger rows; refunds append a new reverse pair rather than mutating the original payment entries.
- `FiscalLedger.ledgerEntries(ServiceIdentity, AccountId)` exposes ordered immutable account history only after an exact account read-authority check.
- Raised the SQLite schema to v15 with durable registered Service Identities and to v16 with immutable exact account/capability grants.
- Service registration records stable identity, owner Mod ID, display name, and registration time. Replay returns the original registration and changed metadata fails closed.
- Registration grants no write access. Capabilities are separate for detailed account reads, Reservations, Escrows, Budgets, Bill issue/funding, payments, refunds, and compensation, and each grant is limited to one exact account.
- Grant administration records administrator identity, idempotent request ID, target service, capability, account, reason, and timestamp. Changed reuse of the same administrator/request pair conflicts.
- Normal callers construct `FiscalLedger` and `PaymentCoordinator` only through authorized factories. Package-local constructors remain for direct tests of the fiscal state machines themselves.
- Authorization runs before SQLite preparation or LC application. An unauthorized settlement creates no transaction row, leaves its Reservation unchanged, and makes zero external-payment calls.
- Detailed balances, holds, ledger entries, Escrows, payment transactions, and recovery audit require `READ_ACCOUNT` on the exact source account. Recovery advancement itself remains an internal server responsibility and is not blocked by later grant changes.
- Raised the SQLite schema to v17 with immutable capability-revocation records. Revocation deactivates a grant without deleting its provenance; re-authorizing the same service/capability/account creates a distinct new grant.
- Raised the SQLite schema to v18 with immutable Service Identity state-change records. `DISABLED` immediately overrides every read/write grant, and a later audited `ENABLED` change restores only the grants that remain active.
- Revocations and service state changes carry administrator identity, stable request ID, reason, and timestamp. Exact replay returns the original record and changed reuse conflicts.
- Disabling a service blocks all new caller reads and writes but does not block Civic's internal recovery loop from finishing an already externally applied payment, preventing administrative changes from stranding moved LC funds.
- Added opaque owner-bound fiscal sessions. Public `FiscalLedger` and `PaymentCoordinator` factories now require a session, every caller identity must match that session before protected reads, authorization, SQLite preparation, or LC application, and package-local raw construction remains limited to state-machine tests.
- Production session opening resolves the first uniquely owned external caller class through the loaded NeoForge scan data and binds the session to the actual `ModContainer`. Unknown or ambiguous ownership fails closed, and the registered `ownerModId` must exactly match the resolved container.
- Raised the SQLite schema to v19 with immutable audited Service Identity registration metadata: administrator identity, stable request ID, reason, and registration time commit atomically with the service row. Pre-v19 registrations receive explicit `civiceconomy-legacy` backfill provenance.
- Added OP/console-only `civic economy admin service` commands for register, grant, revoke, disable, enable, list, and show. Registration rejects unloaded owner Mods; administrator identity is derived from the real command source rather than user input.
- Administration commands enqueue SQLite work on the dedicated `Civic-Economy-SQLite` executor and report completion back on the server thread. Command parsing and permission checks never synchronously execute SQLite on the Minecraft server thread.
- Added immutable authorization views exposing the registered owner, registration audit, current service state, exact account grants, and revocation status without letting command code query SQLite directly.
- Raised the SQLite schema to v20 with persistent `NationApplication` / `PENDING` founding applications, immutable terminal transitions, Candidate Member affiliations, non-reusable Candidate Online Evidence, durable activation preparation, and Capital storage.
- Application creation requires the current FTB Team head, binds only an unregistered team, snapshots Candidate Members atomically, creates no Nation/Citizenship/Treasury, and enforces one PENDING application per team plus one active candidate affiliation per player.
- Candidate evidence clips completed online intervals to the application lifetime, candidate affiliation, configured observation window, and observation time. The original interval UUID is the global claim key, so cancellation, expiry, replay, and later applications cannot reuse it.
- Cancellation and expiry atomically close candidate affiliations and record immutable transition provenance without deleting claimed evidence. Expiry attributes only through the application deadline, not the later scheduler execution time.
- Added a durable Nation activation state machine. Formal activation requires the configured Effective Candidate count; only a server-composed `DEBUG WORLD` policy can reduce that requirement to one, and the request contains no caller-controlled debug flag.
- Activation persists `PREPARED`, idempotently provisions the real LC National Treasury, records `TREASURY_PROVISIONED`, then atomically commits the permanent NationId, all Candidate Member Citizenship periods, Capital, application `ACTIVATED` state, transition audit, and activation completion.
- A crash after external Treasury creation but before Civic records it replays the same deterministic account identity. Until Civic commit, the application remains PENDING and no Nation or Citizenship is visible.
- Added `/civic economy nation apply` for ordinary players. The server derives the applicant UUID and the single owned non-player FTB Team from the live FTB manager, snapshots immutable team facts on the server thread, and queues all SQLite work on `Civic-Economy-SQLite`.
- Repeating the apply command while the same team already has a PENDING application returns that application instead of creating a second row. Players cannot submit a team UUID or owner string through the command.
- Added `/civic economy nation status`, `/civic economy nation cancel <reason>`, and `/civic economy nation activate`. All actor, FTB Team, Capital dimension, and Capital chunk facts are derived by the server; no command accepts caller-supplied identity or Capital coordinates.
- Activation requires the player's current chunk to be a real FTB Chunks claim owned by the same real FTB Team. SQLite activation remains on `Civic-Economy-SQLite`, while real LC National Treasury `SavedData` provisioning is marshalled back to the Minecraft server thread without making the server thread wait for SQLite.
- Added the persistent world-scoped `DEBUG WORLD` marker and `/civic debug status`, `/civic debug enable`, and `/civic debug enable confirm`. Integrated servers require the cheating single-player world owner and explicit confirmation.
- Dedicated servers omit DEBUG WORLD writes by default. The command is registered only when the JVM starts with `-Dciviceconomy.allowDedicatedDebugWorld=true`, and still requires a player OP. Enabling is permanent for the Civic dataset and emits a server-wide red warning plus persistent startup log warnings.
- Added deterministic automatic Nation Application expiry. Startup and one-minute lifecycle scans only enqueue work; due-application queries, evidence claiming, candidate cleanup, and immutable `EXPIRED` transitions run on `Civic-Economy-SQLite` with request ID `automatic-expiry:<applicationId>`.
- Player cancellation now claims all eligible Candidate Online Evidence through the configured 60-day window before closing Candidate affiliations. This makes cancellation replay-safe and prevents the same completed online interval from supporting a later application even when the player never ran `nation status` first.
- `/civic economy nation status` now claims current eligible evidence idempotently and reports Effective Candidate count versus the formal/debug threshold, founding mode, state, and expiry.
- Raised the SQLite schema to v21 with durable Citizenship Correction Grace episodes and to v22 with explicit resolution audit reasons plus migration/backfill for the exercised v21 development shape.
- Added server-authoritative Citizenship Reconciliation. A formal Citizen missing from the bound FTB Team enters a durable two-day Correction Grace; Provider authority and Effective Citizen contribution stop immediately while the formal Citizenship remains open for correction.
- Returning before the deadline resolves the episode as `RESTORED` without replacing the Citizenship. Remaining absent at the deadline closes Citizenship exactly at the deadline, resolves the episode as `CITIZENSHIP_ENDED`, and preserves deterministic leave/resolution request identities across restart windows.
- `FtbTeamsNationProvider` now requires formal current Citizenship, live membership in the Nation's exact bound FTB Team, and no active Correction Grace. New FTB Team members are never auto-enrolled as Citizens; the current FTB owner remains the temporary political head fact.
- Effective Citizen calculation subtracts every Correction Grace suspension interval. Time before departure and after a restoration can contribute; time during an active or completed grace cannot.
- Runtime reconciliation first lists registered Nations on `Civic-Economy-SQLite`, snapshots exact FTB Team facts on the Minecraft server thread, and returns immutable facts to the SQLite executor. Missing Team facts skip reconciliation and fail closed instead of converting every Citizen to stateless.
- Added Nation-level Effective Citizen aggregation with explainable per-Citizen detail. The calculator enumerates formal Citizenship history for one stable `NationId`, applies the existing Citizenship/Correction Grace/online-time rules, includes only time actually attributed to that Nation, and reports exact effective-Citizen count plus fractional population equivalent.
- Added `/civic economy nation population`. The command derives the caller's current formal Nation server-side, queues all SQLite work on `Civic-Economy-SQLite`, and reports each Citizen's attributed online milliseconds and contribution without consulting raw FTB Team membership counts.
- Raised the SQLite schema to v23 with immutable Nation Fiscal Permission grants and separate immutable revocations. Every grant is scoped to one stable Nation, one Citizen UUID, and one exact permission; service/request replay is unique, changed replay conflicts, active duplicate scope is rejected, and reauthorization after revocation creates new provenance.
- Added server-authoritative Nation fiscal governance. Grant/revoke requires the real FTB Team owner to also resolve through the formal Citizenship Provider for the same Nation; the target must be an Effective Citizen of that exact Nation, and active grants stop being effective when Provider authority is suspended.
- Added `/civic economy nation role list|grant|revoke`. Caller identity and Nation are derived from the real command source plus live FTB facts, target scope uses a stable player UUID that is revalidated against the Provider, and all SQLite work runs on `Civic-Economy-SQLite`.
- Added a pure Territory Free Allocation policy module. It consumes the explainable Nation Effective Citizen population, applies configurable base and per-Effective-Citizen chunk parameters with checked arithmetic, and never reads raw FTB Team membership.
- Raised the SQLite schema to v24 with append-only Territory Free Allocation policy versions. Each version records the trusted actor, service/request identity, base chunks, per-Effective-Citizen chunks, reason, record time, and a unique future effective time; policy reads select the latest version effective at the requested instant.
- Added OP/console-only `/civic economy admin territory policy show|schedule`. Writes run on `Civic-Economy-SQLite`, reject immediate/past effect, and use a conservative `0 + 0 × Effective Citizen` fallback until the first persisted policy becomes effective.

## In progress

- Expose each Nation's explainable current Territory Free Allocation, then wire it into FTB Chunks claim prepayment.

## Not yet completed

- SQLite migrations beyond schema v24, scheduled/shutdown backup creation, backup rotation, live database replacement and administrator restore workflow.
- Delegated/custom fiscal-role policy, capital rebinding, liquidation, and Nation lifecycle restrictions.
- Withdrawals, configurable multi-person approval policy, and broader audit.
- Cumulative Net Issuance, Issuance Hard Cap, National Issuance Quota, Registered Mint, material custody, and destruction/correction flows.
- FTB Chunks territory prepayment, maintenance, validity, continuity, transfer, restoration, and force-load charging.
- National Strength, Registered Facility, Create production accounting, Global Reference Price, and conservative scoring adapters.
- Remaining gameplay commands, menus, public reports, recovery tools, DEBUG adjustment/cycle/fault-injection tools, and persistent HUD/report warning surfaces.
- Client runtime, playable single-player loop, and multiplayer core loop.
- Installation, configuration, player, administrator, debugging, upgrade, backup, and recovery documentation.
- Release JAR verification, clean worktree, push, and draft pull request.

## Verification evidence

### Unit fixtures

- `gradlew.bat test --tests org.civiceconomy.compat.CompatibilityMatrixTest` passed on 2026-07-14.
- Six public-interface behaviors passed: supported required versions, missing required mod, unknown required version, missing required probe class, supported Create, and unknown Create.

### Artifact and metadata inspection

- `gradlew.bat resolveRuntimeDependencies` resolved the exact LC and FTB jars plus Architectury `13.0.8`.
- Direct jar inspection confirmed these classes in the real artifacts:
  - LC: `BankAPI`, `IBankAccount`;
  - FTB Teams: `FTBTeamsAPI`;
  - FTB Chunks: `FTBChunksAPI`, `ClaimedChunkManager`;
  - Create: `ProcessingRecipe`.
- Direct `META-INF/neoforge.mods.toml` inspection confirmed runtime versions `1.21-2.3.0.5`, `2101.1.10`, `2101.1.20`, and Create `6.0.6`.
- These checks prove artifact contents and metadata only; they do not prove gameplay behavior.

### Compilation and build

- `gradlew.bat build` passed on 2026-07-14 and produced a development JAR.
- This is compilation/build evidence only, not installability or gameplay evidence.
- JAR inspection confirmed `META-INF/jarjar/sqlite-jdbc-3.50.3.0.jar` and NeoForge jar-in-jar metadata.
- After the Civic fiscal-account slice, `gradlew.bat clean test build --no-daemon --console=plain` passed and produced `build/libs/civiceconomy-0.1.0-probe.jar` containing the Mixin config, all GameTests, generated structure fixture, and jar-in-jar SQLite driver.
- After the compensation/recovery-audit slice, `gradlew.bat clean build --no-daemon --console=plain` passed from fresh outputs.
- After the general-ledger/schema-v14 slice, `gradlew.bat clean build --no-daemon --console=plain` passed from fresh outputs.
- After the production LC account-balance slice, `gradlew.bat clean build --no-daemon --console=plain` passed from fresh outputs.
- After the service-authorization/schema-v16 slice, `gradlew.bat clean build --no-daemon --console=plain` passed from fresh outputs.
- After the authorization-lifecycle/schema-v18 slice, `gradlew.bat clean build --no-daemon --console=plain` passed from fresh outputs.
- After the owner-bound-session/trusted-administration/schema-v19 slice, `gradlew.bat clean build --no-daemon --console=plain` passed from fresh outputs; the full JUnit/SQLite suite executed 105 tests with zero failures.
- After the Nation Application/activation/schema-v20 slice, `gradlew.bat clean build --no-daemon --console=plain` passed from fresh outputs; the full JUnit/SQLite suite executed 113 tests with zero failures.
- After the player Nation Application command slice, `gradlew.bat clean build --no-daemon --console=plain` again passed all 113 JUnit/SQLite tests.
- After the command-driven Nation activation/cancellation and DEBUG WORLD authority slice, `gradlew.bat clean build --no-daemon --console=plain` passed from fresh outputs; the full JUnit/SQLite suite executed 116 tests with zero failures.
- After automatic Nation Application expiry and cancellation evidence hardening, `gradlew.bat clean build --no-daemon --console=plain` passed from fresh outputs; the full JUnit/SQLite suite executed 117 tests with zero failures.
- After Citizenship Reconciliation/schema-v22, `gradlew.bat clean build --no-daemon --console=plain` passed from fresh outputs; the full JUnit/SQLite suite executed 122 tests with zero failures.
- After the Nation Effective Citizen population slice, `gradlew.bat clean build --no-daemon --console=plain` passed from fresh outputs; the full JUnit/SQLite suite executed 123 tests with zero failures.
- After the Nation Fiscal Permission/schema-v23 slice, `gradlew.bat clean build --no-daemon --console=plain` passed from fresh outputs; the full JUnit/SQLite suite executed 127 tests with zero failures.
- After the pure Territory Free Allocation policy slice, `gradlew.bat clean build --no-daemon --console=plain` passed from fresh outputs; the full suite executed 128 tests with zero failures. This is domain/build evidence only; claim-event integration is not yet implemented.
- After persistent Territory Free Allocation policy/schema-v24 and its OP command, `gradlew.bat clean build --no-daemon --console=plain` passed from fresh outputs; the full suite executed 129 tests with zero failures.
- Current development JAR: `D:\ImportantFileFolder\Minecraft\AiMods\Civic Economy mods\build\libs\civiceconomy-0.1.0-probe.jar`, 14,743,041 bytes, SHA-256 `4AC3CE8F85CD89F7B28157DFD6B8476D757D7693086ACA8F329BF17722E5FDB9`. It remains a development artifact until all v1 completion gates pass.

### SQLite integration

- `gradlew.bat test --tests org.civiceconomy.persistence.CivicDatabaseTest` passed against temporary on-disk SQLite files using the real Xerial driver.
- Verified WAL mode, schema v24, persisted identity across close/reopen, migration from a genuine schema-v3 fixture through current Nation, Citizenship, Citizenship Correction Grace, Nation Fiscal Permission grant/revocation, Territory Free Allocation policy versions, online-time, Nation Application/evidence/activation, release, partial-settlement, refund, compensation, recovery-audit, Escrow, Budget, Fiscal Bill, general-ledger, audited Service Identity registration, capability-grant, revocation, and service-state storage, foreign-world rejection, and unknown-schema rejection.
- `CivicDatabaseBackupTest` uses real temporary on-disk SQLite databases and the real Xerial native backup implementation. It verifies a live `300`-unit Reservation snapshot remains unchanged after the source advances to `500`, and that a restored database is usable through the public persistence and fiscal interfaces.
- The same integration test verifies that a foreign world/dependency identity and an unsupported schema version are rejected before a restore destination is published.
- `gradlew.bat test --tests org.civiceconomy.fiscal.PaymentRecoveryTest --tests org.civiceconomy.persistence.CivicDatabaseTest --tests org.civiceconomy.persistence.CivicDatabaseBackupTest --no-daemon --console=plain` passed on 2026-07-14.
- The same focused schema-v3 recovery/database suite was re-run on clean commit `4371d2f` in an isolated worktree on 2026-07-14: 22 tests passed (`PaymentRecoveryTest` 14, `CivicDatabaseTest` 4, `CivicDatabaseBackupTest` 4). The shared checkout's unrelated owner-bound authorization RED test was preserved and excluded from this evidence.
- These are real database integration tests, not mocks. Automatic scheduling/rotation, live replacement of the authoritative database, and a process-death restore drill remain unverified.

### Fiscal domain and database integration

- `MoneyAmountTest` verifies negative rejection, checked overflow, nonnegative subtraction, and exact LC minor-unit arithmetic.
- `FiscalLedgerTest` uses real temporary SQLite databases to verify durable Reservation replay, changed-balance replay stability, insufficient-funds rejection without a row, conflicting-payload rejection, and active/available balances.
- The concurrent oversubscription test passed ten repeated runs: two simultaneous 700-unit holds against 1,000 units produce exactly one success and one active 700-unit Reservation.
- `PaymentRecoveryTest` uses a real temporary SQLite database and controlled external-payment adapter to verify two restart windows: after `EXTERNAL_APPLIED`, and after external application before Civic can record it.
- Recovery after `EXTERNAL_APPLIED` performs no second external call; ambiguous recovery retries the same transaction UUID, producing two adapter attempts but one idempotent economic effect.
- Release tests verify exact replay across restart, reason-payload conflict rejection, immediate available-balance restoration, and rejection while a payment is ambiguous or already committed.
- Partial-settlement tests verify `500 -> 300 -> 0` remaining-hold behavior, replay stability, release of only the remaining `300`, over-settlement rejection at `301`, and restart recovery of a committed `200` payment with `300` still reserved.
- Refund integration tests verify exact-once full refund replay, cumulative `200 + 300` partial refunds, rejection above the refundable ceiling, ambiguous refund retry with one economic effect, and `EXTERNAL_APPLIED` recovery without another external call.
- Compensation integration tests use a real temporary SQLite database and a controlled idempotent external adapter. They verify `EXTERNAL_APPLIED -> COMPENSATING -> COMPENSATED`, a crash after reverse transfer, three adapter attempts but only two economic effects, unchanged Reservation value, durable request identity/reason, and ordered start/completion audit records.
- Ambiguous-payment recovery tests verify that external confirmation and Civic commit audit records are durable, ordered, and not duplicated by a second recovery pass.
- `FiscalLedgerEscrowTest` uses real temporary SQLite databases and the public fiscal interface. It verifies atomic `400`-unit Escrow creation, replay across reopen, exact `600` available balance, `150 + 250` partial/final settlement, cancellation release, deadline-only expiry, and idempotent expiry replay.
- `FiscalLedgerBudgetTest` uses real temporary SQLite databases and the public fiscal interface. It verifies draft persistence with no hold, atomic approval and replay, exact `400` Reservation creation, `150 + 250` spend progression, partial-spend release, deadline expiry, and synchronized Budget/Escrow terminal states.
- `FiscalLedgerBillTest` uses real temporary SQLite databases and the public fiscal/payment interfaces. It verifies issue/funding replay, exact payer holds, exposed required beneficiary, redirected-payment rejection, `100 + 200` payment progression, partial-payment cancellation, and due-date expiry.
- `FiscalLedgerEntriesTest` uses a real temporary SQLite database and the public account-history interface. It verifies paired payment entries, exact source/counterparty/direction/kind fields, replay non-duplication, and a separate reverse pair for refunds without mutating original entries.
- `FiscalAuthorizationTest` uses real temporary SQLite databases and the authorized fiscal interfaces. It verifies zero default authority, audited registration/grant replay and conflicts, persistence across reopen, exact account/capability isolation, protected detailed reads, terminal-state replay, unauthorized settlement with no SQLite transaction or external effect, immutable revocation/re-grant, service disable/re-enable, recovery completion after disable, owner mismatch rejection, and cross-identity impersonation rejection before external payment.
- `NeoForgeCallerModResolverTest` verifies unique scanned-class ownership resolution and fail-closed unknown/ambiguous callers. `AsyncOnlineTimeWriterTest` verifies administration database work runs on the dedicated SQLite thread.
- `NationApplicationRegistryTest` uses real temporary SQLite databases through public Nation interfaces. It verifies head-only persistent application creation, candidate snapshots without Citizenship, exact-once clipped evidence, cancellation/expiry cleanup with permanent interval ownership, formal minimum-candidate denial, trusted DEBUG WORLD bypass, atomic activation outputs, replay, and recovery after Treasury creation before Civic recording.
- The SQLite crash-recovery tests use a controlled external-payment adapter to force exact failure windows; real LC National Treasury commit, refund, and compensation evidence is recorded separately below.

### Real Lightman's Currency integration

- `LightmansCurrencyPaymentsTest` verifies the adapter interface with a boundary fake: replay moves money once and an insufficient partial withdrawal is restored without an applied marker.
- `LightmansCurrencyAccountBalancesTest` verifies exact LC minor-unit propagation through the public balance interface and fail-closed malformed-player, unknown-player, and unknown non-player behavior using a boundary fake only at the LC lookup seam.
- `gradlew.bat runGameTestServer --no-daemon --console=plain` passed all 11 required GameTests against the real pinned LC/FTB runtime after the service-authorization/schema-v16 slice.
- After the owner-bound-session/trusted-administration/schema-v19 slice, the same command passed all 13 required GameTests. The added tests inspect the real server dispatcher and execute a real console registration command, then verify its owner, derived administrator identity, request ID, and reason in the world-bound SQLite database.
- After the Nation Application/activation/schema-v20 slice, the same command passed all 14 required GameTests. The added test runs the activation coordinator against real SQLite and the real LC `SavedData` National Treasury adapter, then reads the new Treasury's exact zero balance through the production LC account interface. Its FTB Team input is an in-memory boundary fixture, not a real FTB founding flow.
- After the player Nation Application command slice, the same command passed all 15 required GameTests. The added test creates an exact-version real FTB head-owned Party Team fixture, executes the real player command twice, and observes exactly one seven-day PENDING application in the world-bound SQLite database. Reflection is confined to constructing the headless FTB fixture; production lookup uses only public FTB APIs.
- After the command-driven activation/cancellation slice, the default dedicated GameTest run passed all 17 required tests. One test creates a real head-owned FTB Party Team, records candidate evidence off-thread, claims the proposed Capital through real FTB Chunks, enables the persistent DEBUG WORLD fixture, executes the real activation command, and verifies the permanent Nation, founder Citizenship, Capital, and exact-zero real LC National Treasury. A second test executes the real cancellation command and verifies immutable actor/reason audit plus closure of every Candidate affiliation.
- A second 17/17 GameTest run with `-Dciviceconomy.allowDedicatedDebugWorld=true` proved the startup-level permission registers the dedicated `debug status` and `debug enable` command tree. The default run proved the same write command tree is absent without that startup permission.
- After lifecycle expiry wiring, the default GameTest run passed all 18 required tests. The added test creates a short-lived PENDING application through the authoritative background database executor, waits for the real server tick scheduler to enqueue expiry, and verifies deterministic service/request audit, `EXPIRED` state, and closed Candidate affiliation in the world-bound SQLite database. The log records the processor on `Civic-Economy-SQLite`, not the server thread.
- After Citizenship Reconciliation/schema-v22, the default GameTest run passed all 19 required tests. The added test creates a real head-owned FTB Party Team with a second formal Citizen, persists the Nation/Citizenship off-thread, removes that member through the exact FTB team object on the server thread, and verifies the runtime scheduler creates a durable two-day active Correction Grace while leaving formal Citizenship open.
- After adding the Nation population command, the default GameTest run again passed all 19 required tests. Its command-tree assertion proves the real NeoForge dispatcher exposes `population` alongside `apply`, `status`, `cancel`, and `activate`.
- After the Nation Fiscal Permission command slice, the default GameTest run passed all 20 required tests. The added test creates a real FTB Team owner, persists the Nation and formal Citizenship off-thread, executes the real `nation role grant` command using the server-derived actor and stable target UUID, and verifies one exact `APPROVE_BUDGET` grant plus audit reason in the world-bound SQLite database.
- After the Territory Free Allocation policy command slice, the default GameTest run passed all 21 required tests. The added test executes the real OP/console schedule command and verifies the server-derived administrator identity, request ID, parameters, future effective time, and reason in the world-bound schema-v24 SQLite database.
- `CitizenshipReconciliationTest` uses real temporary SQLite databases to verify immediate Provider suspension, restoration of the same Citizenship, deadline-effective Citizenship leave, no automatic Citizenship for new FTB members, and restart recovery after the Citizenship leave committed but before the Correction Grace resolved.
- `EffectiveCitizenCalculatorTest` verifies online time during Correction Grace is excluded immediately while eligible time before the grace remains attributed.
- The GameTest used real `BankDataCache` player accounts, `BankAPI.BankWithdrawFromServer`, `BankAPI.BankDepositFromServer`, `CoinValue.fromNumber(CoinAPI.MAIN_CHAIN, ...)`, and the applied Mixin.
- It verified source `1000 → 700`, recipient `25 → 325`, replay with the same transaction UUID caused no second movement, and the marker survived LC NBT save/reload.
- The fiscal-account access test created both a National Treasury and Organization Fiscal Account through the registered LC account source, resolved both references, and verified native player access, salary targeting, salary permission, and persistence were denied.
- The interest test funded a National Treasury with 500 LC minor units, invoked LC's interest hook with a multiplier that would otherwise double it, and verified the balance remained exactly 500.
- The treasury settlement test used a real temporary SQLite database and real LC accounts. Funding player `1000 → 300`, National Treasury `0 → 700 → 400`, and recipient `25 → 325`; ambiguous recovery finished `CIVIC_COMMITTED` and cleared the Reservation without a second economic effect.
- The same treasury recovery GameTest now opens a durable Escrow rather than a bare Reservation and verifies the real LC recovery commit advances it to `SETTLED`.
- The treasury recovery GameTest now starts from a durable Budget draft and approval, then proves the real LC recovery path advances Budget `APPROVED -> SPENT` and Escrow `RESERVED -> SETTLED` without a second economic effect.
- The refund GameTest paid `300` from the real National Treasury to a real LC player account and reversed it exactly once: funding player remained `300`, National Treasury returned to `700`, recipient returned to `25`, replay returned the same committed refund, and the original payment recorded `300` refunded minor units.
- The compensation GameTest paid `300` from a real National Treasury, entered durable compensation, reversed through real LC, crashed before Civic recorded the reverse, reopened SQLite, and recovered without another economic effect. The National Treasury returned to `700`, the recipient returned to `25`, the `300`-unit Reservation remained active, replay returned `COMPENSATED`, and both audit actions survived reopen.
- The Fiscal Bill GameTest seeded a real LC player bank with `500`, read the exact value through the production adapter, proved an unknown player lookup fails without creating an LC account, issued and funded a `300` fee to a real National Treasury, paid it exactly once, and verified player `500 -> 200`, Treasury `0 -> 300`, Bill `PAID`, Escrow `SETTLED`, and zero remaining hold.
- The same real LC Fiscal Bill GameTest verifies exactly one payer `OUTFLOW` and one Treasury `INFLOW` ledger entry after payment replay.
- The Budget, payment, refund, compensation, and Fiscal Bill GameTests now register durable services, grant only the exact capabilities/accounts needed by each scenario, and execute through the authorized production factories.
- Repeated GameTest runs honor markers persisted by earlier runs; each isolated test therefore uses a fresh transaction UUID while replaying it twice within that run.
- LC logs `GameProfileCache` errors when its synthetic offline player accounts are generated under the headless GameTest server, whose profile cache is null. The required test still passes and normal dedicated-server startup does not show this condition.

### Real FTB Teams and FTB Chunks integration

- Exact artifact bytecode inspection with `javap` confirmed the adapter signatures in FTB Teams `2101.1.10` and FTB Chunks `2101.1.20`.
- Official source inspection confirmed `TeamManager.getTeamForPlayerID` returns the effective party team, while `getPlayerTeamForPlayerID` always returns the personal team.
- Official FTB Chunks source inspection confirmed `ChunkTeamData.claim(..., checkOnly)` uses `true` for simulation and that `ClaimedChunkEvent` before-events may be simulated and must not mutate Civic state.
- The FTB Teams GameTest created or reused a real fixed server team and verified exact UUID, effective UUID, owner, members, and team type through `FtbTeamsAdapter`.
- During the RED/GREEN run, `checkOnly=true` returned a successful simulation without a claim. The final GameTest uses `checkOnly=false`, reads the real claim through `FtbChunksAdapter`, verifies ownership/position/time/force-load facts, and unclaims it in cleanup.
- Nation registry tests use real temporary on-disk SQLite databases. They verify stable replay across reopen, distinct Nation IDs for distinct teams, request-payload conflicts, unknown-team rejection without a row, and strict one-to-one binding.
- Nation provider tests use an in-memory adapter only at the true external FTB seam. They verify that unregistered teams are not Nations, current head/member facts are refreshed, registered citizens resolve to the stable Nation ID, and a missing bound team fails closed without erasing the Nation.
- The seventh GameTest uses the real pinned FTB Teams runtime plus a real temporary SQLite database. It verifies that the temporary provider does not recognize the FTB fixture before registration, then resolves it after registration while keeping the Civic `NationId` distinct from the FTB Team UUID.
- Citizenship tests use real temporary on-disk SQLite databases. They verify join and leave replay across reopen, request-payload conflicts, unknown-Nation rejection, one active Citizenship per player, stale-leave rejection, ordered historical periods, cooldown rejection before expiry, and transfer success exactly at expiry.
- Online-time tests use real temporary SQLite databases. They verify exact-once replay across restart, payload-conflict rejection, overlap rejection, endpoint adjacency, history ordering, and clipped rolling-window totals.
- Effective Citizen tests use the public Citizenship and online-time interfaces over real SQLite. They verify that an interval crossing a Nation transfer is split by actual affiliation time, old observations fall outside the 60-day window, and ten attributed hours still cap contribution at `1` under the default eight-hour threshold.
- Session-accumulator tests verify checkpoint/logout adjacency and duplicate-login stability without Minecraft or threads.
- The eighth GameTest verifies that the real server lifecycle has created and persisted the world identity and runtime SQLite database, then routes an unconnected `ServerPlayer` through Civic's login/logout delegate and waits for the background writer to persist exactly one positive interval.
- A full NeoForge player-login event was intentionally not posted in that GameTest: LC and FTB attempt real client payload synchronization and reject the embedded headless connection. Actual global-event delivery remains part of later `runClient`/multiplayer verification; the Civic delegate and asynchronous persistence path are real.

### Dedicated-server runtime

- `gradlew.bat runServer` started a real NeoForge dedicated server with LC `1.21-2.3.0.5`, FTB Teams `2101.1.10`, FTB Chunks `2101.1.20`, FTB Library `2101.1.32`, Architectury `13.0.8`, and no Create.
- Civic logged `compatibility check passed; Create production scoring is disabled`.
- Minecraft logged `Done (5.304s)!` before the task-owned process was stopped.
- `gradlew.bat runServer -PincludeCreate=true` loaded the same required stack plus Create `6.0.6`, embedded Flywheel `1.0.4`, and embedded Ponder `1.0.56` in an isolated run directory.
- Civic logged `compatibility check passed; Create production scoring is enabled`.
- Minecraft logged `Done (4.165s)!` before the monitored task-owned process tree was stopped.
- After the Civic fiscal-account slice, `gradlew.bat runServer --no-daemon --console=plain` again reached `Done (3.389s)!` with Create absent and production scoring disabled.
- After the same slice, `gradlew.bat runServer --no-daemon --console=plain -PincludeCreate=true` loaded Create `6.0.6`, enabled production scoring, and reached `Done (3.061s)!`.
- After the FTB adapter slice, the no-Create dedicated server reached `Done (3.491s)!` and the Create `6.0.6` server reached `Done (3.285s)!`.
- After the Nation registry/provider slice, the no-Create dedicated server reached `Done (3.441s)!`; Civic reported Create absent and fiscal-core startup enabled.
- The same Nation build with Create `6.0.6` reached `Done (3.402s)!`; Civic reported Create production scoring enabled.
- After the Citizenship slice, the no-Create dedicated server reached `Done (3.443s)!` and the Create `6.0.6` server reached `Done (3.293s)!`, with the same compatibility behavior.
- After the online-time and Effective Citizen slice, the no-Create dedicated server reached `Done (3.440s)!` and the Create `6.0.6` server reached `Done (3.330s)!`.
- After the server-runtime slice, the no-Create dedicated server reached `Done (3.361s)!` and then logged world-bound SQLite/online observation active. The Create `6.0.6` server reached `Done (3.374s)!` and logged the same runtime activation immediately afterward.
- After Reservation release and partial settlement, the no-Create server reached `Done (3.399s)!` and the Create `6.0.6` server reached `Done (3.252s)!`; both then opened the migrated world-bound SQLite runtime successfully.
- After the refund/schema-v9 slice, the no-Create server reached `Done (3.571s)!` with production scoring disabled, and the Create `6.0.6` server reached `Done (3.400s)!` with production scoring enabled; both opened the migrated world-bound SQLite runtime successfully.
- After the compensation/schema-v10 slice, the no-Create server reached `Done (3.593s)!` with production scoring disabled, and the Create `6.0.6` server reached `Done (3.418s)!` with production scoring enabled; both opened the migrated world-bound SQLite runtime successfully.
- After the Escrow/schema-v11 slice, the no-Create server reached `Done (3.455s)!` with production scoring disabled, and the Create `6.0.6` server reached `Done (3.153s)!` with production scoring enabled; both opened the migrated world-bound SQLite runtime successfully.
- After the Budget/schema-v12 slice, the no-Create server reached `Done (11.647s)!` with production scoring disabled, and the Create `6.0.6` server reached `Done (9.887s)!` with production scoring enabled; both opened the migrated world-bound SQLite runtime successfully after fresh spawn preparation.
- After the Fiscal Bill/schema-v13 slice, the no-Create server reached `Done (4.141s)!` with production scoring disabled, and the Create `6.0.6` server reached `Done (3.793s)!` with production scoring enabled; both opened the migrated world-bound SQLite runtime successfully.
- After the general-ledger/schema-v14 slice, the no-Create server reached `Done (3.890s)!` with production scoring disabled, and the Create `6.0.6` server reached `Done (3.618s)!` with production scoring enabled; both opened the migrated world-bound SQLite runtime successfully.
- After the production LC account-balance slice, the no-Create server reached `Done (4.079s)!` with production scoring disabled, and the Create `6.0.6` server reached `Done (4.048s)!` with production scoring enabled; both opened the world-bound SQLite runtime successfully.
- After the service-authorization/schema-v16 slice, the no-Create server reached `Done (3.798s)!` with production scoring disabled, and the Create `6.0.6` server reached `Done (3.878s)!` with production scoring enabled; both migrated/opened the world-bound SQLite runtime successfully.
- After the authorization-lifecycle/schema-v18 slice, the no-Create server reached `Done (4.176s)!` with production scoring disabled, and the Create `6.0.6` server reached `Done (3.777s)!` with production scoring enabled; both migrated/opened the world-bound SQLite runtime successfully.
- After the owner-bound-session/trusted-administration/schema-v19 slice, the no-Create server reached `Done (4.229s)!` with production scoring disabled, and the Create `6.0.6` server reached `Done (4.160s)!` with production scoring enabled; both migrated/opened the world-bound SQLite runtime successfully.
- After the Nation Application/activation/schema-v20 slice, the no-Create server reached `Done (3.751s)!` with production scoring disabled, and the Create `6.0.6` server reached `Done (3.751s)!` with production scoring enabled; both opened the schema-v20 world-bound SQLite runtime and enabled buffered online-time observation.
- After the command-driven activation/cancellation and DEBUG WORLD authority slice, the no-Create server reached `Done (3.873s)!` with production scoring disabled, and the Create `6.0.6` server reached `Done (3.540s)!` with production scoring enabled. Both opened the world-bound schema-v20 SQLite runtime; task-owned process trees were stopped after startup confirmation.
- After automatic application expiry wiring, the no-Create server reached `Done (3.814s)!` with production scoring disabled, and the Create `6.0.6` server reached `Done (3.415s)!` with production scoring enabled. Both opened the world-bound SQLite runtime without an expiry failure; task-owned process trees were stopped after startup confirmation.
- After Citizenship Reconciliation/schema-v22, the no-Create server reached `Done (3.618s)!` with production scoring disabled, and the Create `6.0.6` server reached `Done (3.651s)!` with production scoring enabled. Both migrated/opened the world-bound database and started background expiry/reconciliation without failure; task-owned process trees were stopped after startup confirmation.
- After the Nation population slice, the no-Create server loaded the real LC/FTB stack, disabled Create production scoring, and reached `Done (12.761s)!`. The isolated Create `6.0.6` server enabled production scoring and reached `Done (4.899s)!`; both task-owned process trees were stopped after startup confirmation.
- After the Nation Fiscal Permission/schema-v23 slice, the no-Create server loaded the real LC/FTB stack, migrated the world-bound database, disabled Create production scoring, and reached `Done (11.841s)!`. The Create `6.0.6` server enabled production scoring and reached `Done (9.568s)!`; both task-owned process trees were stopped after startup confirmation.
- After Territory Free Allocation policy/schema-v24, the no-Create server migrated/opened the world-bound SQLite database, disabled Create production scoring, and reached `Done (11.329s)!`. The Create `6.0.6` server enabled production scoring and reached `Done (10.329s)!`; both task-owned process trees were stopped after startup confirmation.

### Environment note

- The local Microsoft Java 21 trust store rejected the Gradle/GitHub certificate path. Validation commands used Java's Windows root store via `JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE`.
- The Gradle 9.2.1 distribution was accepted only after its SHA-256 matched Gradle's published `72f44c9f8ebcb1af43838f45ee5c4aa9c5444898b3468ab3f4af7b6076c5bc3f` checksum.

## Known defects and risks

- The startup compatibility probe still checks class presence rather than reflective method descriptors; method/event semantics are currently evidenced by exact compilation, source inspection, and real GameTests.
- FTB claim/team event subscriptions are not yet wired; current adapters are conservative server-side queries only. In particular, future `BEFORE_CLAIM` handling must remain side-effect-free because FTB fires it for simulations.
- Citizenship Reconciliation currently scans at startup and once per minute rather than subscribing to exact FTB membership events. Provider reads still fail closed immediately from live membership facts, but persistence of the Correction Grace can lag by at most one scan interval.
- The two-day Correction Grace, seven-day transfer cooldown, and one-minute scan interval are implementation constants pending server policy configuration and documentation.
- Online sessions checkpoint every minute, so an ungraceful process death can conservatively lose at most the unflushed tail of each active session. It cannot credit offline time. The interval is currently an implementation constant and still needs configuration/documentation.
- Nation Application expiry scans run at startup and every minute, with all SQLite work off-thread. The scan interval and 60-day evidence window are still implementation constants pending server policy configuration.
- Graceful `ServerStoppedEvent` waits up to ten seconds for queued SQLite writes and close. This is bounded shutdown lifecycle work, not regular tick work, but timeout behavior still needs a controlled fault GameTest.
- `NationRegistry.register` remains a low-level legacy registration primitive used by earlier adapter tests. New player founding uses Nation Application commands, but legacy registration must be removed or restricted before release so it cannot become a second gameplay path.
- Nation writes are serialized through the single authoritative `CivicDatabase` instance and protected by SQLite unique constraints. If multiple database instances were incorrectly used as concurrent writers, a constraint race could surface as a generic persistence failure rather than the corresponding domain conflict; the server composition must keep one instance, and explicit race mapping remains future hardening.
- The command-driven GameTest proves real FTB head authorization, real FTB Chunks Capital ownership, real LC Treasury provisioning, and SQLite activation in one running server. Actual client interaction and a process-death restart drill across LC `SavedData` plus SQLite remain unverified.
- DEBUG WORLD authority, persistence, startup permission, command confirmation, broadcast warning, and startup log warning are implemented. Persistent HUD/admin-screen/public-report warnings and the remaining debug adjustment, quota, cycle, inspection, reset, and fault-injection tools are not yet implemented.
- Activation recovery is verified across SQLite reopen with an idempotent Treasury adapter after the external-create/SQLite-record window. Actual process-death ordering across LC `SavedData` and SQLite still requires a controlled server restart drill.
- Applied player-transfer UUIDs are retained indefinitely in LC bank data; a safe retention/compaction policy must be designed without reopening replay windows.
- The treasury GameTests prove real LC/SQLite commit and compensation recovery inside one running server across controlled reopen windows. They do not yet prove actual process-death ordering across Civic fiscal-account `SavedData`, LC `BankDataCache`, and SQLite files.
- Compensation requires an exact `COMPENSATE_PAYMENT` account grant. Grant/revocation/service-state administration is now OP/console gated and audited; a direct player compensation command is still intentionally absent.
- Escrow expiry is a durable operation but is not yet invoked by a lifecycle scheduler; automatic expiry scanning must run off regular Minecraft ticks.
- Refunds currently preserve the original Reservation's settled amount and therefore do not reopen or reclassify a settled Escrow. Refund-to-Escrow accounting semantics require an explicit rule before public reporting relies on them.
- `openEscrow` now requires exact `MANAGE_ESCROW` scope, but it still creates an already-approved `RESERVED` arrangement. Player-facing flows should prefer Budget approval or another governance decision rather than exposing this integration primitive directly.
- Budget approval now requires a registered service with exact `MANAGE_BUDGET` scope, but it still represents one approval action. Configurable thresholds, distinct approvers, delayed policy changes, and player/government-role authorization are not yet wired.
- A draft Budget past its expiry rejects approval but remains stored as `DRAFT`; automatic stale-draft classification/cleanup is not yet scheduled.
- Fiscal Bill issuance, funding, cancellation, and payment now use separate exact service capabilities/account scopes. Player/government-role authentication is still required before commands can map a human actor to those services.
- An unfunded Bill past its due date rejects funding but remains stored as `ISSUED`; automatic overdue classification and scheduled expiry are not yet wired.
- Refunds of paid Bill transactions currently leave Bill and Escrow settled-state totals unchanged, matching the broader unresolved refund-to-Escrow accounting rule.
- Schema v14 does not backfill ledger rows for transactions already committed by an older development schema because those rows lack trustworthy original commit timestamps. This is acceptable for the pre-release development baseline but requires an explicit upgrade policy before any public release migration.
- Schemas v15-v19 do not infer or backfill grants from historical service-identity strings. Existing development callers must register and receive explicit grants before using authorized factories; pre-v19 registration audit is explicitly marked as legacy backfill.
- Grants support immutable revocation and services support disable/re-enable; grants still have no automatic expiry. Production sessions now verify `ownerModId` against the actual calling NeoForge Mod container. The resolver relies on exact loaded scan data and direct caller stack provenance; unknown, generated-only, or multiply-owned caller classes fail closed.
- Nation Fiscal Permissions are persistently grantable/revocable and immediately fail closed when formal Provider authority is unavailable. The current governance command intentionally reserves grant/revoke to the live Nation head; delegated `MANAGE_FISCAL_ROLES`, configurable role bundles, approval-policy thresholds, and human-facing fiscal operations still need wiring.
- Territory Free Allocation policy versions are persistent and delayed, but the conservative fallback is zero until an OP schedules a version. Nation-facing allowance display and FTB claim-event charging are not yet wired, so the policy currently moves no funds and grants no claim bypass.
- Current ledger entry kinds cover only committed `PAYMENT` and `REFUND` transfers. Issuance, destruction, withdrawal, public-fund allocation, and administrator/debug adjustments require explicit future entry kinds rather than being forced into payment semantics.
- Online backup is currently a synchronous persistence operation with no server lifecycle scheduler. Future periodic and shutdown callers must run it outside regular Minecraft ticks, rotate snapshots, and surface failures without replacing the live database in place.
- Runtime-generated `run/` data is local evidence and must never be committed.
- Lightman's Currency defaults include issuance and interest mechanisms that must be explicitly disabled or blocked before a playable release can satisfy currency conservation.

## Next step

Expose the current policy-plus-population Territory Free Allocation to Nation players, then use the same result in FTB Chunks `BEFORE_CLAIM` prepayment without treating FTB membership counts as population.
