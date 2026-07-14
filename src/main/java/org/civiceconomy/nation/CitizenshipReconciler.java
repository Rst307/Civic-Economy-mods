package org.civiceconomy.nation;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.persistence.CivicDatabase;

public final class CitizenshipReconciler {
    private static final ServiceIdentity SERVICE =
            new ServiceIdentity("civiceconomy-citizenship-reconciliation");
    private static final String REASON =
            "Formal Citizen is absent from the Nation's bound FTB Team";

    private final NationRegistry nations;
    private final CivicDatabase database;
    private final CitizenshipRegistry citizenships;
    private final CitizenshipCorrectionGraceRegistry corrections;
    private final NationTeamDirectory teams;
    private final Duration correctionGrace;
    private final Duration transferCooldown;
    private final Clock clock;

    public CitizenshipReconciler(
            CivicDatabase database,
            NationTeamDirectory teams,
            Duration correctionGrace,
            Duration transferCooldown,
            Clock clock) {
        if (database == null || teams == null || correctionGrace == null
                || transferCooldown == null || clock == null
                || correctionGrace.isZero() || correctionGrace.isNegative()) {
            throw new IllegalArgumentException(
                    "Citizenship reconciliation dependencies and positive grace are required");
        }
        this.database = database;
        this.nations = new NationRegistry(database, teams);
        this.citizenships = new CitizenshipRegistry(database, transferCooldown, clock);
        this.corrections = new CitizenshipCorrectionGraceRegistry(database, clock);
        this.teams = teams;
        this.correctionGrace = correctionGrace;
        this.transferCooldown = transferCooldown;
        this.clock = clock;
    }

    public CitizenshipReconciliationResult reconcile(NationId nationId) {
        RegisteredNation nation = nations.find(nationId)
                .orElseThrow(() -> new UnknownNationException(nationId));
        NationTeam team = teams.find(nation.ftbTeamId())
                .orElseThrow(() -> new NationFactsUnavailableException(nationId));
        int started = 0;
        int restored = 0;
        int ended = 0;
        Set<java.util.UUID> handledCitizenships = new HashSet<>();
        for (CitizenshipCorrectionGrace grace : corrections.activeForNation(nationId)) {
            handledCitizenships.add(grace.citizenshipId());
            Citizenship current = citizenships.current(grace.playerId())
                    .filter(value -> value.citizenshipId().equals(grace.citizenshipId()))
                    .orElse(null);
            if (current == null) {
                Citizenship endedCitizenship = citizenships.history(grace.playerId()).stream()
                        .filter(value -> value.citizenshipId().equals(grace.citizenshipId()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException(
                                "Correction Grace references unknown Citizenship "
                                        + grace.citizenshipId()));
                resolveEnded(
                        grace,
                        java.time.Instant.ofEpochMilli(
                                endedCitizenship.endedAtEpochMillis().orElseThrow()));
                ended++;
            } else if (team.citizens().contains(grace.playerId())) {
                corrections.resolve(new ResolveCitizenshipCorrectionGrace(
                        SERVICE,
                        "correction-grace-restore:" + grace.graceId(),
                        grace.graceId(),
                        CitizenshipCorrectionResolution.RESTORED,
                        "Citizen returned to the Nation's bound FTB Team"));
                restored++;
            } else if (!clock.instant().isBefore(grace.deadline())) {
                CitizenshipRegistry deadlineCitizenships = new CitizenshipRegistry(
                        database,
                        transferCooldown,
                        Clock.fixed(grace.deadline(), ZoneOffset.UTC));
                deadlineCitizenships.leave(new LeaveCitizenship(
                        SERVICE,
                        "correction-grace-end:" + grace.graceId(),
                        grace.playerId(),
                        nationId));
                resolveEnded(grace, grace.deadline());
                ended++;
            }
        }
        for (Citizenship citizenship : citizenships.currentForNation(nationId)) {
            if (handledCitizenships.contains(citizenship.citizenshipId())
                    || team.citizens().contains(citizenship.playerId())) {
                continue;
            }
            corrections.start(new StartCitizenshipCorrectionGrace(
                    SERVICE,
                    "correction-grace-start:" + citizenship.citizenshipId() + ":" + clock.millis(),
                    citizenship.citizenshipId(),
                    citizenship.playerId(),
                    nationId,
                    nation.ftbTeamId(),
                    clock.instant().plus(correctionGrace),
                    REASON));
            started++;
        }
        return new CitizenshipReconciliationResult(started, restored, ended);
    }

    private void resolveEnded(
            CitizenshipCorrectionGrace grace, java.time.Instant resolvedAt) {
        new CitizenshipCorrectionGraceRegistry(
                        database, Clock.fixed(resolvedAt, ZoneOffset.UTC))
                .resolve(new ResolveCitizenshipCorrectionGrace(
                        SERVICE,
                        "correction-grace-ended:" + grace.graceId(),
                        grace.graceId(),
                        CitizenshipCorrectionResolution.CITIZENSHIP_ENDED,
                        "Correction Grace expired while the Citizen remained outside the bound FTB Team"));
    }
}
