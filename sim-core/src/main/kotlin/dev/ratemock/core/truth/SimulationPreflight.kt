package dev.ratemock.core.truth

enum class PreflightSeverity { PASS, WARNING, BLOCKED }

data class PreflightCheck(val key: String, val severity: PreflightSeverity)

data class SimulationPreflightInput(
    val notificationAllowed: Boolean,
    val simulationDirectoryWritable: Boolean,
    val historyReadable: Boolean,
    val historyExists: Boolean,
    val validProfile: Boolean,
)

data class SimulationPreflightResult(val checks: List<PreflightCheck>) {
    val blocked: Boolean get() = checks.any { it.severity == PreflightSeverity.BLOCKED }
}

object SimulationPreflight {
    fun check(input: SimulationPreflightInput): SimulationPreflightResult = SimulationPreflightResult(
        listOf(
            PreflightCheck("notifications", if (input.notificationAllowed) PreflightSeverity.PASS else PreflightSeverity.BLOCKED),
            PreflightCheck("simulation_directory", if (input.simulationDirectoryWritable) PreflightSeverity.PASS else PreflightSeverity.BLOCKED),
            PreflightCheck("history", when {
                !input.historyExists -> PreflightSeverity.PASS
                input.historyReadable -> PreflightSeverity.PASS
                else -> PreflightSeverity.WARNING
            }),
            PreflightCheck("parameters", if (input.validProfile) PreflightSeverity.PASS else PreflightSeverity.BLOCKED),
            PreflightCheck("system_injection", PreflightSeverity.PASS),
        ),
    )
}
