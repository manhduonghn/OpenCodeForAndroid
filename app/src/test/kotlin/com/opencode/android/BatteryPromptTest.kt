package com.opencode.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryPromptTest {

    @Test
    fun freshInstallIsAlwaysDue() {
        assertTrue(shouldOfferBatteryPrompt(shown = false, lastMs = 0L, nowMs = 1_000L))
    }

    @Test
    fun oldInstallWithoutTimestampIsDueOnce() {
        assertTrue(shouldOfferBatteryPrompt(shown = true, lastMs = 0L, nowMs = 1_000L))
    }

    @Test
    fun laterChoiceIsRespectedForOneDay() {
        val shownAt = 10_000L
        assertFalse(
            shouldOfferBatteryPrompt(
                shown = true,
                lastMs = shownAt,
                nowMs = shownAt + BATTERY_PROMPT_COOLDOWN_MS - 1,
            ),
        )
        assertTrue(
            shouldOfferBatteryPrompt(
                shown = true,
                lastMs = shownAt,
                nowMs = shownAt + BATTERY_PROMPT_COOLDOWN_MS,
            ),
        )
    }

    @Test
    fun clockGoingBackwardsDoesNotRetriggerPrompt() {
        assertFalse(
            shouldOfferBatteryPrompt(
                shown = true,
                lastMs = 50_000L,
                nowMs = 10_000L,
            ),
        )
    }
}
