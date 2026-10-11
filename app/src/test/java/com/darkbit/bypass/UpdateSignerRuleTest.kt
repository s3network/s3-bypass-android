package com.darkbit.bypass

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The signature gate decides whether a downloaded file is allowed to become a
 * running application. There is no "warn and continue" branch in it by design,
 * so the cases below are the ones worth pinning.
 *
 * These use the JVM rather than Android APIs, which is deliberate: the rule
 * being tested is the comparison of two signer sets, and that rule has to hold
 * on its own rather than only when a device is attached.
 */
class UpdateSignerRuleTest {

    /** The comparison as the installer applies it, over hex signer strings. */
    private fun signersMatch(mine: Set<String>, theirs: Set<String>): Boolean =
        mine.isNotEmpty() && mine == theirs

    @Test
    fun `the same signer is accepted`() {
        assertTrue(signersMatch(setOf("ab12"), setOf("ab12")))
    }

    @Test
    fun `a different signer is refused`() {
        assertFalse(signersMatch(setOf("ab12"), setOf("cd34")))
    }

    @Test
    fun `an unsigned archive is refused`() {
        // The dangerous case: an empty set equals another empty set, so a naive
        // equality check would wave through a file with no signature at all.
        assertFalse(signersMatch(setOf("ab12"), emptySet()))
        assertFalse(signersMatch(emptySet(), setOf("ab12")))
        assertFalse(signersMatch(emptySet(), emptySet()))
    }

    @Test
    fun `a subset is not the same signer`() {
        // Rotation adds a signer rather than replacing one, so the sets must
        // match exactly. Accepting a subset would let a file signed only by a
        // withdrawn key through.
        assertFalse(signersMatch(setOf("ab12", "cd34"), setOf("ab12")))
        assertTrue(signersMatch(setOf("ab12", "cd34"), setOf("cd34", "ab12")))
    }
}