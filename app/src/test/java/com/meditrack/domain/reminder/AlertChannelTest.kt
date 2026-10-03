package com.meditrack.domain.reminder

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The rules that decide which channel a reminder is posted on.
 *
 * These matter more than they look: Android freezes a channel's sound at creation, so the id is the
 * *only* thing that can make a new tone take effect. Get it wrong and the user picks a new tone and
 * keeps hearing the old one - which is precisely the bug this feature exists to remove.
 */
class AlertChannelTest {

    @Test
    fun `every tone gets its own channel`() {
        val ids = ReminderTone.entries.map { AlertChannel.id(it, vibrationEnabled = true) }
        assertThat(ids.toSet()).hasSize(ReminderTone.entries.size)
    }

    @Test
    fun `turning vibration off moves to a different channel`() {
        // A channel's vibration is frozen too, so this cannot be the same id.
        assertThat(AlertChannel.id(ReminderTone.BELL, vibrationEnabled = true))
            .isNotEqualTo(AlertChannel.id(ReminderTone.BELL, vibrationEnabled = false))
    }

    @Test
    fun `a custom sound file gets its own channel, stable for the same uri`() {
        val one = AlertChannel.id(ReminderTone.BELL, true, "content://media/audio/1")
        val again = AlertChannel.id(ReminderTone.BELL, true, "content://media/audio/1")
        val other = AlertChannel.id(ReminderTone.BELL, true, "content://media/audio/2")
        assertThat(one).isEqualTo(again)
        assertThat(one).isNotEqualTo(other)
        assertThat(one).isNotEqualTo(AlertChannel.id(ReminderTone.BELL, true))
    }

    @Test
    fun `a blank custom uri is treated as no custom uri`() {
        assertThat(AlertChannel.id(ReminderTone.BELL, true, "   "))
            .isEqualTo(AlertChannel.id(ReminderTone.BELL, true))
    }

    @Test
    fun `only our own channels are recognised as the audible one`() {
        assertThat(AlertChannel.isAlert(AlertChannel.id(ReminderTone.BEEP, true))).isTrue()
        assertThat(AlertChannel.isAlert("meditrack_reminder_vibrate")).isFalse()
        assertThat(AlertChannel.isAlert("meditrack_guard")).isFalse()
        assertThat(AlertChannel.isAlert(null)).isFalse()
    }

    @Test
    fun `stale lists every other alert channel plus the legacy one, and nothing else`() {
        val keep = AlertChannel.id(ReminderTone.CHIME, vibrationEnabled = true)
        val existing = listOf(
            keep,
            AlertChannel.id(ReminderTone.BELL, vibrationEnabled = true),   // an earlier tone
            AlertChannel.id(ReminderTone.BELL, vibrationEnabled = false),  // an earlier vibration setting
            AlertChannel.LEGACY_ALERT,
            "meditrack_reminder_vibrate",                                  // other channels are untouched
            "meditrack_guard",
        )
        assertThat(AlertChannel.stale(existing, keep)).containsExactly(
            AlertChannel.id(ReminderTone.BELL, vibrationEnabled = true),
            AlertChannel.id(ReminderTone.BELL, vibrationEnabled = false),
            AlertChannel.LEGACY_ALERT,
        )
    }

    @Test
    fun `nothing is stale when only the current channel exists`() {
        val keep = AlertChannel.id(ReminderTone.MARIMBA, vibrationEnabled = false)
        assertThat(AlertChannel.stale(listOf(keep, "meditrack_missed"), keep)).isEmpty()
    }
}
