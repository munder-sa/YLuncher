package com.gt8d.launcher.ui.panel

import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager

sealed interface CellIndicator {
    data object Unavailable : CellIndicator
    data object NoSim : CellIndicator
    data object Airplane : CellIndicator
    data class Signal(val bars: Int, val label: String?) : CellIndicator
}

fun cellIndicatorOf(
    hasTelephony: Boolean,
    airplane: Boolean,
    simReady: Boolean,
    level: Int?,
    networkType: Int,
    overrideType: Int,
): CellIndicator {
    if (!hasTelephony) return CellIndicator.Unavailable
    if (airplane) return CellIndicator.Airplane
    if (!simReady) return CellIndicator.NoSim
    val bars = (level ?: 0).coerceIn(0, 4)
    return CellIndicator.Signal(bars, if (bars == 0) null else cellLabelOf(networkType, overrideType))
}

fun cellLabelOf(networkType: Int, overrideType: Int): String? {
    if (overrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
        overrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED) return "5G"
    return when (networkType) {
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_LTE -> "4G"
        TelephonyManager.NETWORK_TYPE_UMTS,
        TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_HSPAP,
        TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A,
        TelephonyManager.NETWORK_TYPE_EVDO_B,
        TelephonyManager.NETWORK_TYPE_EHRPD,
        TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "3G"
        TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_EDGE,
        TelephonyManager.NETWORK_TYPE_CDMA,
        TelephonyManager.NETWORK_TYPE_1xRTT,
        TelephonyManager.NETWORK_TYPE_IDEN,
        TelephonyManager.NETWORK_TYPE_GSM -> "2G"
        else -> null
    }
}
