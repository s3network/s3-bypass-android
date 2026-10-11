package com.darkbit.bypass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The comparison is the part of the updater that fails quietly.
 *
 * A wrong "older" answer means the user is never told about a real update and
 * nothing on screen indicates why. The obvious implementation — compare the
 * strings — gets it wrong at 1.1.10, which is the first release where it
 * would actually bite.
 */
class UpdateCheckerTest {

    @Test
    fun `a later patch is newer`() {
        assertTrue(UpdateChecker.isNewer("1.1.2", "1.1.1"))
    }

    @Test
    fun `double digits compare numerically, not as text`() {
        // "1.1.9" > "1.1.10" as strings. Getting this backwards would make
        // every tenth release invisible.
        assertTrue(UpdateChecker.isNewer("1.1.10", "1.1.9"))
        assertFalse(UpdateChecker.isNewer("1.1.9", "1.1.10"))
    }

    @Test
    fun `a later minor and major are both newer`() {
        assertTrue(UpdateChecker.isNewer("1.2.0", "1.1.9"))
        assertTrue(UpdateChecker.isNewer("2.0.0", "1.99.99"))
    }

    @Test
    fun `the same version is not an update`() {
        assertFalse(UpdateChecker.isNewer("1.1.1", "1.1.1"))
        assertEquals(0, UpdateChecker.compare("1.1.1", "1.1.1"))
    }

    @Test
    fun `different lengths are padded rather than read as missing`() {
        assertTrue(UpdateChecker.isNewer("1.2", "1.1.9"))
        assertFalse(UpdateChecker.isNewer("1.1", "1.1.1"))
    }

    @Test
    fun `a malformed component cannot invent an update`() {
        // A tag like "1.2.3-beta" is still newer than 1.2.2, but a component
        // that is not a number must never compare as greater.
        assertTrue(UpdateChecker.isNewer("1.2.3-beta", "1.2.2"))
        assertFalse(UpdateChecker.isNewer("1.2.3", "1.2.3-beta"))
        assertFalse(UpdateChecker.isNewer("garbage", "1.0.0"))
    }

    @Test
    fun `a v prefix is not part of the comparison`() {
        assertTrue(UpdateChecker.isNewer("v1.2.0", "1.1.0"))
    }
}