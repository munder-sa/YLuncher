package com.gt8d.launcher.settings

import com.gt8d.launcher.split.SplitMode
import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionsTest {
    @Test fun permissions_listsItems() {
        val common = listOf(PermissionItem.Home, PermissionItem.Notification, PermissionItem.Overlay,
            PermissionItem.Browser, PermissionItem.Bluetooth, PermissionItem.UsageAccess)
        assertEquals(common, permissionItems(SplitMode.Vendor))
        assertEquals(common, permissionItems(SplitMode.LeftOnly))
        assertEquals(common + PermissionItem.Accessibility, permissionItems(SplitMode.AppPair))
    }
}
