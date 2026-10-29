package com.gt8d.launcher.link

import org.junit.Assert.*
import org.junit.Test

class RecentFrontTest {
    @Test fun resumed_isFront() { assertTrue(RecentFront.isFront(true, null, 2000L)) }
    @Test fun pausedWithinRecent_isFront() { assertTrue(RecentFront.isFront(false, 1000L, 2000L)) }
    @Test fun pausedTooLongAgo_isNotFront() { assertFalse(RecentFront.isFront(false, 1000L, 2001L)) }
    @Test fun neverPaused_notResumed_isNotFront() { assertFalse(RecentFront.isFront(false, null, 2000L)) }
    @Test fun pausedInFuture_isNotFront() { assertFalse(RecentFront.isFront(false, 2001L, 2000L)) }
}
