package org.civiceconomy.nation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.junit.jupiter.api.Test;

class OnlineSessionAccumulatorTest {
    @Test
    void checkpointsAndLogoutProduceAdjacentCompletedIntervals() {
        UUID playerId = UUID.fromString("881b6765-a70a-4d04-a0aa-c85c68a502dd");
        long loginTime = Instant.parse("2026-07-14T08:00:00Z").toEpochMilli();
        OnlineSessionAccumulator sessions =
                new OnlineSessionAccumulator(new ServiceIdentity("civiceconomy-server"));

        sessions.login(playerId, loginTime);
        List<RecordOnlineTime> checkpoint =
                sessions.checkpoint(Instant.parse("2026-07-14T08:05:00Z").toEpochMilli());
        List<RecordOnlineTime> logout =
                sessions.logout(playerId, Instant.parse("2026-07-14T08:07:00Z").toEpochMilli());

        assertEquals(1, checkpoint.size());
        assertEquals(1, logout.size());
        assertEquals(Duration.ofMinutes(5).toMillis(), checkpoint.getFirst().endedAtEpochMillis()
                - checkpoint.getFirst().startedAtEpochMillis());
        assertEquals(checkpoint.getFirst().endedAtEpochMillis(), logout.getFirst().startedAtEpochMillis());
        assertEquals(Duration.ofMinutes(2).toMillis(), logout.getFirst().endedAtEpochMillis()
                - logout.getFirst().startedAtEpochMillis());
        assertTrue(sessions.activePlayers().isEmpty());
        assertTrue(!checkpoint.getFirst().requestId().equals(logout.getFirst().requestId()));
    }

    @Test
    void duplicateLoginDoesNotResetAnActiveSession() {
        UUID playerId = UUID.fromString("03031a5e-b013-431d-8236-e87b3d046300");
        long loginTime = Instant.parse("2026-07-14T09:00:00Z").toEpochMilli();
        OnlineSessionAccumulator sessions =
                new OnlineSessionAccumulator(new ServiceIdentity("civiceconomy-server"));

        sessions.login(playerId, loginTime);
        sessions.login(playerId, Instant.parse("2026-07-14T09:02:00Z").toEpochMilli());
        RecordOnlineTime completed = sessions
                .logout(playerId, Instant.parse("2026-07-14T09:05:00Z").toEpochMilli())
                .getFirst();

        assertEquals(loginTime, completed.startedAtEpochMillis());
        assertEquals(Duration.ofMinutes(5).toMillis(),
                completed.endedAtEpochMillis() - completed.startedAtEpochMillis());
    }
}
