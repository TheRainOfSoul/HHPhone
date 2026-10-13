package com.tutpro.baresip.plus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HHPushPolicyTest {
    private val idle = HHPushPolicy.GRACE_MS + 1
    @Test fun foreignAccountKeepsAlways() = assertTrue(HHPushPolicy.keep(foreign = true, visible = false, inCall = false, idleMs = idle))
    @Test fun visibleKeeps() = assertTrue(HHPushPolicy.keep(false, visible = true, inCall = false, idleMs = idle))
    @Test fun callKeeps() = assertTrue(HHPushPolicy.keep(false, false, inCall = true, idleMs = idle))
    @Test fun graceKeeps() = assertTrue(HHPushPolicy.keep(false, false, false, idleMs = HHPushPolicy.GRACE_MS - 1))
    @Test fun idleSleeps() = assertFalse(HHPushPolicy.keep(false, false, false, idleMs = idle))
}
