package org.civiceconomy.platform.neoforge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CivicDebugWorldCommandsTest {
    @Test
    void integratedServersRegisterDebugWritesWithoutDedicatedPermission() {
        assertTrue(DebugWorldCommandRegistrationPolicy.shouldRegister(true, false));
    }

    @Test
    void dedicatedServersFailClosedWithoutStartupPermission() {
        assertFalse(DebugWorldCommandRegistrationPolicy.shouldRegister(false, false));
    }

    @Test
    void startupPermissionAllowsDedicatedDebugCommandRegistration() {
        assertTrue(DebugWorldCommandRegistrationPolicy.shouldRegister(false, true));
    }
}
