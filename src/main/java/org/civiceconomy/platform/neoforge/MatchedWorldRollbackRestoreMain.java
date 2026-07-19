package org.civiceconomy.platform.neoforge;

import java.nio.file.Path;

public final class MatchedWorldRollbackRestoreMain {
    private MatchedWorldRollbackRestoreMain() {}

    public static void main(String[] arguments) {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("Expected the owned run directory");
        }
        Path runDirectory = Path.of(arguments[0]).toAbsolutePath().normalize();
        MatchedWorldRollbackSnapshot.restore(
                runDirectory,
                runDirectory.resolve("matched-world-rollback/snapshot-world"),
                runDirectory.resolve("world"));
    }
}
