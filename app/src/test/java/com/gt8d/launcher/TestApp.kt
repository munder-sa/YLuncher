package com.gt8d.launcher

import android.app.Application
import androidx.test.espresso.IdlingPolicies
import com.gt8d.launcher.ui.theme.ScreenSemantics
import java.util.concurrent.TimeUnit

class TestApp : Application() {
    override fun onCreate() {
        ScreenSemantics.detailed = true
        super.onCreate()
        IdlingPolicies.setMasterPolicyTimeout(10, TimeUnit.SECONDS)
        IdlingPolicies.setIdlingResourceTimeout(10, TimeUnit.SECONDS)
    }
}
