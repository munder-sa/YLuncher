package com.gt8d.launcher.apps

import org.junit.Assert.assertEquals
import org.junit.Test

class OwnPackagesTest {
    @Test fun main_isOwn() = assertEquals(true, OwnPackages.isOwn("com.gt8d.launcher"))
    @Test fun panelHost_isOwn() = assertEquals(true, OwnPackages.isOwn("com.gt8d.launcher.panel"))
    @Test fun other_isNotOwn() = assertEquals(false, OwnPackages.isOwn("com.example.other"))
    @Test fun null_isNotOwn() = assertEquals(false, OwnPackages.isOwn(null))
}
