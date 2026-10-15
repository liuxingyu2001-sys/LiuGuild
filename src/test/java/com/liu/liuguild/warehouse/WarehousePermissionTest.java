package com.liu.liuguild.warehouse;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WarehousePermissionTest {
    @Test void membershipDoesNotReplacePermission() {
        assertFalse(WarehouseService.allowed(false, false, true));
        assertFalse(WarehouseService.allowed(false, true, true));
        assertTrue(WarehouseService.allowed(true, false, true));
    }
    @Test void unrelatedGuildRequiresAdmin() {
        assertFalse(WarehouseService.allowed(true, false, false));
        assertTrue(WarehouseService.allowed(true, true, false));
    }
}
