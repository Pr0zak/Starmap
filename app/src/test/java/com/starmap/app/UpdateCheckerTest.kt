package com.starmap.app

import com.starmap.app.update.UpdateChecker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The comparison that decides whether a GitHub release is newer than what's installed. */
class UpdateCheckerTest {

    @Test
    fun detectsNewerVersions() {
        assertTrue(UpdateChecker.isNewer("1.0.1", "1.0.0"))
        assertTrue(UpdateChecker.isNewer("1.1.0", "1.0.9"))
        assertTrue(UpdateChecker.isNewer("2.0.0", "1.9.9"))
        assertTrue(UpdateChecker.isNewer("v1.2.3", "1.2.2")) // tolerates a leading v
    }

    @Test
    fun rejectsSameOrOlderVersions() {
        assertFalse(UpdateChecker.isNewer("1.0.0", "1.0.0"))
        assertFalse(UpdateChecker.isNewer("1.0.0", "1.0.1"))
        assertFalse(UpdateChecker.isNewer("1.9.9", "2.0.0"))
        assertFalse(UpdateChecker.isNewer("1.0", "1.0.0")) // 1.0 == 1.0.0
    }
}
