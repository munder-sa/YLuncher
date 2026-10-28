package com.gt8d.launcher.spike

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SpikeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SpikeActions.run(
            context.applicationContext,
            intent.getStringExtra("step") ?: "",
            intent.extras,
        )
    }
}
