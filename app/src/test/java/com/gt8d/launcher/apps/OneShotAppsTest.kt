package com.gt8d.launcher.apps

import org.junit.Assert.assertEquals
import org.junit.Test

class OneShotAppsTest {
    @Test fun backCar_isOneShot() = assertEquals(true, OneShotApps.contains("com.ds.backcar"))
    @Test fun gptLive_isNotOneShot() = assertEquals(false, OneShotApps.contains("local.aibox.gptlive"))
    @Test fun chatGpt_isNotOneShot() = assertEquals(false, OneShotApps.contains("com.openai.chatgpt"))
    @Test fun null_isNotOneShot() = assertEquals(false, OneShotApps.contains(null))
}
