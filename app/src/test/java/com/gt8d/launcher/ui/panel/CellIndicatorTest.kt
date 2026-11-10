package com.gt8d.launcher.ui.panel

import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CellIndicatorTest {
    private fun indicator(
        hasTelephony: Boolean = true,
        airplane: Boolean = false,
        simReady: Boolean = true,
        level: Int? = 3,
        networkType: Int = TelephonyManager.NETWORK_TYPE_LTE,
        overrideType: Int = TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE,
    ) = cellIndicatorOf(hasTelephony, airplane, simReady, level, networkType, overrideType)

    @Test fun noTelephony_isUnavailableRegardlessOfOtherInputs() {
        for (airplane in listOf(false, true)) for (simReady in listOf(false, true)) {
            for (level in listOf(null, -1, 0, 3, 9)) {
                for (network in listOf(TelephonyManager.NETWORK_TYPE_UNKNOWN, TelephonyManager.NETWORK_TYPE_LTE)) {
                    for (override in listOf(TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE,
                        TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA)) {
                        assertEquals(CellIndicator.Unavailable,
                            indicator(false, airplane, simReady, level, network, override))
                    }
                }
            }
        }
    }

    @Test fun airplane_takesPrecedenceOverSimState() {
        for (simReady in listOf(false, true)) {
            assertEquals(CellIndicator.Airplane, indicator(airplane = true, simReady = simReady))
        }
    }

    @Test fun simNotReady_isNoSim() {
        assertEquals(CellIndicator.NoSim, indicator(simReady = false))
    }

    @Test fun lteLevelThree_isFourGWithThreeBars() {
        assertEquals(CellIndicator.Signal(3, "4G"), indicator())
    }

    @Test fun nullLevel_hasNoBarsOrLabel() {
        assertEquals(CellIndicator.Signal(0, null), indicator(level = null))
    }

    @Test fun zeroLevel_hidesLabelEvenWithFiveGOverride() {
        assertEquals(CellIndicator.Signal(0, null),
            indicator(level = 0, overrideType = TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA))
    }

    @Test fun levelAboveFour_isClampedToFour() {
        assertEquals(CellIndicator.Signal(4, "4G"), indicator(level = 9))
    }

    @Test fun negativeLevel_isClampedToZeroWithoutLabel() {
        assertEquals(CellIndicator.Signal(0, null), indicator(level = -1))
    }

    @Test fun lteWithNrNsa_isFiveG() {
        assertEquals("5G", cellLabelOf(TelephonyManager.NETWORK_TYPE_LTE,
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA))
    }

    @Test fun lteWithNrAdvanced_isFiveG() {
        assertEquals("5G", cellLabelOf(TelephonyManager.NETWORK_TYPE_LTE,
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED))
    }

    @Test fun nrWithoutOverride_isFiveG() {
        assertEquals("5G", cellLabelOf(TelephonyManager.NETWORK_TYPE_NR,
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE))
    }

    @Test fun lteWithoutOverride_isFourG() {
        assertEquals("4G", cellLabelOf(TelephonyManager.NETWORK_TYPE_LTE,
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE))
    }

    @Test fun lteCarrierAggregation_isFourG() {
        assertEquals("4G", cellLabelOf(TelephonyManager.NETWORK_TYPE_LTE,
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA))
    }

    @Test fun hspap_isThreeG() {
        assertEquals("3G", cellLabelOf(TelephonyManager.NETWORK_TYPE_HSPAP,
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE))
    }

    @Test fun edge_isTwoG() {
        assertEquals("2G", cellLabelOf(TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE))
    }

    @Test fun unknownNetwork_hasNoLabel() {
        assertNull(cellLabelOf(TelephonyManager.NETWORK_TYPE_UNKNOWN,
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE))
    }

    @Test fun iwlan_hasNoLabel() {
        assertNull(cellLabelOf(TelephonyManager.NETWORK_TYPE_IWLAN,
            TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE))
    }

    @Test fun remainingThreeGNetworks_areThreeG() {
        for (network in listOf(TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_EVDO_0, TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_EVDO_B, TelephonyManager.NETWORK_TYPE_EHRPD,
            TelephonyManager.NETWORK_TYPE_TD_SCDMA)) {
            assertEquals("network=$network", "3G", cellLabelOf(network,
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE))
        }
    }

    @Test fun remainingTwoGNetworks_areTwoG() {
        for (network in listOf(TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT, TelephonyManager.NETWORK_TYPE_IDEN,
            TelephonyManager.NETWORK_TYPE_GSM)) {
            assertEquals("network=$network", "2G", cellLabelOf(network,
                TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE))
        }
    }

    @Test fun unrecognizedNetwork_hasNoLabel() {
        assertNull(cellLabelOf(Int.MAX_VALUE, TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE))
    }
}
