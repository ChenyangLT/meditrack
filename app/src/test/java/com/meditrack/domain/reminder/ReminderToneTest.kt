package com.meditrack.domain.reminder

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The bundled tone catalogue: five distinct sounds that ship inside the APK. */
class ReminderToneTest {

    @Test
    fun `there are five tones, each with its own audio resource`() {
        assertThat(ReminderTone.entries).hasSize(5)
        val resources = ReminderTone.entries.map { it.rawRes }
        assertThat(resources.toSet()).hasSize(5)
        assertThat(resources).doesNotContain(0)
    }

    @Test
    fun `every tone is named for the settings screen`() {
        ReminderTone.entries.forEach { tone ->
            assertThat(tone.label.trim()).isNotEmpty()
        }
        assertThat(ReminderTone.entries.map { it.label }.toSet()).hasSize(5)
    }

    @Test
    fun `a stored name round-trips and unknown values fall back to the default`() {
        ReminderTone.entries.forEach { tone ->
            assertThat(ReminderTone.fromName(tone.name)).isEqualTo(tone)
        }
        assertThat(ReminderTone.fromName(null)).isEqualTo(ReminderTone.DEFAULT)
        assertThat(ReminderTone.fromName("")).isEqualTo(ReminderTone.DEFAULT)
        assertThat(ReminderTone.fromName("TONE_FROM_THE_FUTURE")).isEqualTo(ReminderTone.DEFAULT)
    }

    @Test
    fun `the default tone is the one a fresh install hears`() {
        assertThat(ReminderTone.DEFAULT).isEqualTo(ReminderTone.BELL)
    }
}
