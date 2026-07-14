package org.civiceconomy.platform.neoforge;

public final class DebugWorldCommandRegistrationPolicy {
    private DebugWorldCommandRegistrationPolicy() {}

    public static boolean shouldRegister(
            boolean integratedServer, boolean dedicatedStartupPermit) {
        return integratedServer || dedicatedStartupPermit;
    }
}
