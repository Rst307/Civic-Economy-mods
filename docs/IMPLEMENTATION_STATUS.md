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

## In progress

- Add the gameplay Nation-registration policy with team-head authorization, configurable founding eligibility, atomic initial Citizenship assignment, and FTB membership reconciliation.

## Not yet completed

- SQLite migrations beyond schema v6, online backups, restore validation, compensation execution, and recovery audit records.
- Nation-level Effective Citizen aggregation, FTB membership reconciliation and correction grace, registration eligibility, fiscal roles, capital, rebinding, liquidation, and Nation lifecycle restrictions.
- Player-bank balance adapter; budgets, Reservation release/partial settlement, Escrow, refunds, withdrawals, approvals, ledger, audit, and service authorization policy.
- Cumulative Net Issuance, Issuance Hard Cap, National Issuance Quota, Registered Mint, material custody, and destruction/correction flows.
- FTB Chunks territory prepayment, maintenance, validity, continuity, transfer, restoration, and force-load charging.
- National Strength, Registered Facility, Create production accounting, Global Reference Price, and conservative scoring adapters.
- Commands, menus, public reports, administration, recovery tools, and isolated `DEBUG WORLD` implementation.
- Client runtime, GameTest runtime, playable single-player loop, and multiplayer core loop.
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
- Current development JAR: `D:\ImportantFileFolder\Minecraft\AiMods\Civic Economy mods\build\libs\civiceconomy-0.1.0-probe.jar`, 14,459,818 bytes, SHA-256 `B9967B42A53FD6F3F47BD0548E544553158E23448D179C37D5CEBD4453043E83`. This is not yet a release artifact.

### SQLite integration

- `gradlew.bat test --tests org.civiceconomy.persistence.CivicDatabaseTest` passed against temporary on-disk SQLite files using the real Xerial driver.
- Verified WAL mode, schema v6, persisted identity across close/reopen, migration from a genuine schema-v3 fixture through the current Nation, Citizenship, and online-time tables, foreign-world rejection, and unknown-schema rejection.
- These are real database integration tests, not mocks; backup/restore and crash recovery remain unverified.

### Fiscal domain and database integration

- `MoneyAmountTest` verifies negative rejection, checked overflow, nonnegative subtraction, and exact LC minor-unit arithmetic.
- `FiscalLedgerTest` uses real temporary SQLite databases to verify durable Reservation replay, changed-balance replay stability, insufficient-funds rejection without a row, conflicting-payload rejection, and active/available balances.
- The concurrent oversubscription test passed ten repeated runs: two simultaneous 700-unit holds against 1,000 units produce exactly one success and one active 700-unit Reservation.
- `PaymentRecoveryTest` uses a real temporary SQLite database and controlled external-payment adapter to verify two restart windows: after `EXTERNAL_APPLIED`, and after external application before Civic can record it.
- Recovery after `EXTERNAL_APPLIED` performs no second external call; ambiguous recovery retries the same transaction UUID, producing two adapter attempts but one idempotent economic effect.
- The SQLite crash-recovery tests still use a controlled external-payment adapter; real LC payment evidence is recorded separately below and is not yet wired to Civic-owned treasury accounts.

### Real Lightman's Currency integration

- `LightmansCurrencyPaymentsTest` verifies the adapter interface with a boundary fake: replay moves money once and an insufficient partial withdrawal is restored without an applied marker.
- `gradlew.bat runGameTestServer --no-daemon --console=plain -PincludeCreate=false` passed eight required GameTests against the real pinned LC/FTB runtime after the server-runtime slice.
- The GameTest used real `BankDataCache` player accounts, `BankAPI.BankWithdrawFromServer`, `BankAPI.BankDepositFromServer`, `CoinValue.fromNumber(CoinAPI.MAIN_CHAIN, ...)`, and the applied Mixin.
- It verified source `1000 → 700`, recipient `25 → 325`, replay with the same transaction UUID caused no second movement, and the marker survived LC NBT save/reload.
- The fiscal-account access test created both a National Treasury and Organization Fiscal Account through the registered LC account source, resolved both references, and verified native player access, salary targeting, salary permission, and persistence were denied.
- The interest test funded a National Treasury with 500 LC minor units, invoked LC's interest hook with a multiplier that would otherwise double it, and verified the balance remained exactly 500.
- The treasury settlement test used a real temporary SQLite database and real LC accounts. Funding player `1000 → 300`, National Treasury `0 → 700 → 400`, and recipient `25 → 325`; ambiguous recovery finished `CIVIC_COMMITTED` and cleared the Reservation without a second economic effect.
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

### Environment note

- The local Microsoft Java 21 trust store rejected the Gradle/GitHub certificate path. Validation commands used Java's Windows root store via `JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE`.
- The Gradle 9.2.1 distribution was accepted only after its SHA-256 matched Gradle's published `72f44c9f8ebcb1af43838f45ee5c4aa9c5444898b3468ab3f4af7b6076c5bc3f` checksum.

## Known defects and risks

- The startup compatibility probe still checks class presence rather than reflective method descriptors; method/event semantics are currently evidenced by exact compilation, source inspection, and real GameTests.
- FTB claim/team event subscriptions are not yet wired; current adapters are conservative server-side queries only. In particular, future `BEFORE_CLAIM` handling must remain side-effect-free because FTB fires it for simulations.
- Temporary provider citizens are still current FTB Team members, while formal Citizenship now lives in the persistent registry. The provider and FTB membership events are not yet reconciled to that registry, and neither raw membership nor Citizenship alone is an Effective Citizen without rolling online-time evidence.
- Online sessions checkpoint every minute, so an ungraceful process death can conservatively lose at most the unflushed tail of each active session. It cannot credit offline time. The interval is currently an implementation constant and still needs configuration/documentation.
- Graceful `ServerStoppedEvent` waits up to ten seconds for queued SQLite writes and close. This is bounded shutdown lifecycle work, not regular tick work, but timeout behavior still needs a controlled fault GameTest.
- `NationRegistry.register` is currently the durable registration primitive. No gameplay command yet proves the caller is the FTB Team head or enforces the configurable minimum valid-citizen threshold.
- Nation writes are serialized through the single authoritative `CivicDatabase` instance and protected by SQLite unique constraints. If multiple database instances were incorrectly used as concurrent writers, a constraint race could surface as a generic persistence failure rather than the corresponding domain conflict; the server composition must keep one instance, and explicit race mapping remains future hardening.
- Applied player-transfer UUIDs are retained indefinitely in LC bank data; a safe retention/compaction policy must be designed without reopening replay windows.
- The treasury GameTest proves real LC/SQLite recovery inside one running server after the ambiguous adapter window. It does not yet prove process-death ordering across Civic fiscal-account `SavedData`, LC `BankDataCache`, and SQLite files; durable per-account transfer phase evidence or compensation is still required before release.
- Runtime-generated `run/` data is local evidence and must never be committed.
- Lightman's Currency defaults include issuance and interest mechanisms that must be explicitly disabled or blocked before a playable release can satisfy currency conservation.

## Next step

Use the persisted activity evidence for team-head-authorized Nation registration with configurable founding eligibility and atomic initial Citizenship assignment.
