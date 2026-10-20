package com.liu.liuguild.crossserver;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossServerLeaveTest {

    @Test
    void leaveFromAnotherServerIsDetected() {
        assertTrue(CrossServerBus.isRecentLeaveFromOtherServer("server-a", "server-b"));
        assertTrue(CrossServerBus.isRecentLeaveFromOtherServer("Server-A", "server-b"));
    }

    @Test
    void sameServerOrMissingLeaveIsNotAProxySwitch() {
        assertFalse(CrossServerBus.isRecentLeaveFromOtherServer("server-a", "server-a"));
        assertFalse(CrossServerBus.isRecentLeaveFromOtherServer(null, "server-a"));
        assertFalse(CrossServerBus.isRecentLeaveFromOtherServer("", "server-a"));
        assertFalse(CrossServerBus.isRecentLeaveFromOtherServer("server-a", null));
    }
}
