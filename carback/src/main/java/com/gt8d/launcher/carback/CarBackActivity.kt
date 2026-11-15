package com.gt8d.launcher.carback

import android.app.Activity
import android.os.Bundle

class CarBackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CarScreenSwitch.send(this)
        finish()
    }
}
