package com.gt8d.launcher.split

import com.gt8d.launcher.config.SplitRatio

enum class FailReason { EntryMissing, Exception, Timeout, OneSideMissing, NotReady }

sealed interface LaunchResult {
    data object Started : LaunchResult
    data class Failed(val reason: FailReason) : LaunchResult
}

interface SplitLauncher {
    fun open(left: String, right: String, ratio: SplitRatio): LaunchResult
}
