package com.meditrack.data.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Version comparison, which is the part of an update check that silently goes wrong.
 *
 * The failure mode is always the same: comparing versions as strings, so "1.10.0" looks older than
 * "1.9.0" and people stop being offered updates.
 */
class UpdateVersionTest {

    @Test
    fun `a later patch, minor or major version is newer`() {
        assertThat(UpdateVersion.isNewer("1.7.1", "1.7.0")).isTrue()
        assertThat(UpdateVersion.isNewer("1.8.0", "1.7.0")).isTrue()
        assertThat(UpdateVersion.isNewer("2.0.0", "1.99.0")).isTrue()
    }

    @Test
    fun `double digit segments compare as numbers, not as text`() {
        // The whole reason this is not a string comparison.
        assertThat(UpdateVersion.isNewer("1.10.0", "1.9.0")).isTrue()
        assertThat(UpdateVersion.isNewer("1.9.0", "1.10.0")).isFalse()
        assertThat(UpdateVersion.isNewer("1.0.10", "1.0.9")).isTrue()
    }

    @Test
    fun `the same version is not newer, whichever way it is written`() {
        assertThat(UpdateVersion.isNewer("1.8.0", "1.8.0")).isFalse()
        assertThat(UpdateVersion.isNewer("v1.8.0", "1.8.0")).isFalse()
        assertThat(UpdateVersion.isNewer("1.8", "1.8.0")).isFalse()
        assertThat(UpdateVersion.isNewer("1.8.0", "v1.8")).isFalse()
    }

    @Test
    fun `an older release is never offered`() {
        assertThat(UpdateVersion.isNewer("1.6.0", "1.7.0")).isFalse()
        assertThat(UpdateVersion.isNewer("1.7.0", "1.7.0")).isFalse()
    }

    @Test
    fun `pre-release and build metadata are ignored, not treated as newer`() {
        // A "1.8.0-beta" tag on the latest release must not look newer than the installed 1.8.0.
        assertThat(UpdateVersion.isNewer("1.8.0-beta.1", "1.8.0")).isFalse()
        assertThat(UpdateVersion.isNewer("1.8.0-rc2", "1.7.0")).isTrue()
        assertThat(UpdateVersion.isNewer("1.8.0+build.7", "1.8.0")).isFalse()
    }

    @Test
    fun `unparseable input degrades to zero instead of throwing`() {
        // A garbage tag parses as 0.0.0 - older than anything actually installed - so a malformed
        // release can never cause an update prompt, and nothing ever throws.
        assertThat(UpdateVersion.parse("")).containsExactly(0)
        assertThat(UpdateVersion.parse("v")).containsExactly(0)
        assertThat(UpdateVersion.parse("abc")).containsExactly(0)
        assertThat(UpdateVersion.parse("1.x.3")).containsExactly(1, 0, 3)
        assertThat(UpdateVersion.isNewer("abc", "1.0.0")).isFalse()
        assertThat(UpdateVersion.isNewer("", "1.0.0")).isFalse()
    }

    @Test
    fun `display strips the tag prefix for humans`() {
        assertThat(UpdateVersion.display("v1.8.0")).isEqualTo("1.8.0")
        assertThat(UpdateVersion.display(" 1.8.0 ")).isEqualTo("1.8.0")
        assertThat(UpdateVersion.display("V1.8.0")).isEqualTo("1.8.0")
    }
}
