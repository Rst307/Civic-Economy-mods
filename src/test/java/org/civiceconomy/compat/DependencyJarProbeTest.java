package org.civiceconomy.compat;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.URL;
import java.util.List;
import org.junit.jupiter.api.Test;

class DependencyJarProbeTest {
    private static final List<String> REQUIRED_CLASS_ENTRIES = List.of(
            "io/github/lightman314/lightmanscurrency/api/money/bank/BankAPI.class",
            "io/github/lightman314/lightmanscurrency/api/money/bank/IBankAccount.class",
            "io/github/lightman314/lightmanscurrency/api/money/bank/reference/BankReference.class",
            "io/github/lightman314/lightmanscurrency/api/money/bank/source/BankAccountSource.class",
            "dev/ftb/mods/ftbteams/api/FTBTeamsAPI.class",
            "dev/ftb/mods/ftbchunks/api/FTBChunksAPI.class",
            "dev/ftb/mods/ftbchunks/api/ClaimedChunkManager.class",
            "dev/ftb/mods/ftbchunks/api/ClaimedChunk.class",
            "dev/ftb/mods/ftbchunks/api/ChunkTeamData.class",
            "dev/ftb/mods/ftbchunks/api/event/ClaimedChunkEvent.class");

    private static final String CREATE_CLASS_ENTRY =
            "com/simibubi/create/content/processing/recipe/ProcessingRecipe.class";

    @Test
    void pinnedRequiredDependencyJarsContainEveryProbeClass() {
        ClassLoader loader = getClass().getClassLoader();

        assertAll(REQUIRED_CLASS_ENTRIES.stream()
                .map(entry -> () -> assertNotNull(loader.getResource(entry), "Missing real dependency class " + entry)));
    }

    @Test
    void createProbePresenceMatchesTheOptionalRuntimeSelection() {
        URL createClass = getClass().getClassLoader().getResource(CREATE_CLASS_ENTRY);

        assertEquals(Boolean.getBoolean("civic.test.createExpected"), createClass != null);
    }
}
