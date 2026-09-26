package dev.ratemock.core.truth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimulationPreflightTest {
    private fun input(
        notificationAllowed: Boolean = true,
        simulationDirectoryWritable: Boolean = true,
        historyReadable: Boolean = true,
        historyExists: Boolean = false,
        validProfile: Boolean = true,
    ) = SimulationPreflightInput(
        notificationAllowed, simulationDirectoryWritable, historyReadable, historyExists, validProfile,
    )

    @Test
    fun allChecksPassWithoutHistory() {
        val result = SimulationPreflight.check(input())

        assertFalse(result.blocked)
        assertEquals(PreflightSeverity.PASS, result.checks.first { it.key == "system_injection" }.severity)
        assertEquals(PreflightSeverity.PASS, result.checks.first { it.key == "history" }.severity)
    }

    @Test
    fun missingNotificationBlocksStart() {
        val result = SimulationPreflight.check(input(notificationAllowed = false))

        assertTrue(result.blocked)
        assertEquals(PreflightSeverity.BLOCKED, result.checks.first { it.key == "notifications" }.severity)
    }

    @Test
    fun unwritableDirectoryBlocksStart() {
        val result = SimulationPreflight.check(input(simulationDirectoryWritable = false))

        assertTrue(result.blocked)
        assertEquals(PreflightSeverity.BLOCKED, result.checks.first { it.key == "simulation_directory" }.severity)
    }

    @Test
    fun unreadableExistingHistoryWarnsWithoutBlocking() {
        val result = SimulationPreflight.check(input(historyExists = true, historyReadable = false))

        assertFalse(result.blocked)
        assertEquals(PreflightSeverity.WARNING, result.checks.first { it.key == "history" }.severity)
    }

    @Test
    fun invalidProfileBlocksStart() {
        val result = SimulationPreflight.check(input(validProfile = false))

        assertTrue(result.blocked)
        assertEquals(PreflightSeverity.BLOCKED, result.checks.first { it.key == "parameters" }.severity)
    }
}
