package com.gt8d.launcher

import android.app.Application
import androidx.test.espresso.IdlingPolicies
import java.util.concurrent.TimeUnit

class TestApp : Application() {
    override fun onCreate() {
        super.onCreate()
        IdlingPolicies.setMasterPolicyTimeout(10, TimeUnit.SECONDS)
        IdlingPolicies.setIdlingResourceTimeout(10, TimeUnit.SECONDS)
    }
}
