package dev.jobbar

import java.util.UUID

enum class JobRunMode(val label: String) {
    ONE_SHOT("One-shot command"),
    TRACKED("Tracked process");

    override fun toString(): String = label
}

enum class JobType(val label: String) {
    GENERIC("Generic command"),
    ANGULAR("Angular"),
    VITE("Vite"),
    NODE("npm / Node.js"),
    TYPESCRIPT("TypeScript compiler"),
    JEST("Jest"),
    DOCKER_COMPOSE("Docker Compose"),
    SPRING_BOOT("Spring Boot"),
    CUSTOM("Custom");

    override fun toString(): String = label
}

data class JobVariant(
    var label: String = "",
    var value: String = ""
)

data class JobDefinition(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "New job",
    var command: String = "",
    var workingDirectory: String = "\$PROJECT_DIR\$",
    var runMode: JobRunMode = JobRunMode.ONE_SHOT,
    var type: JobType = JobType.GENERIC,
    var reuseConsole: Boolean = true,
    var parameterName: String = "",
    var variants: MutableList<JobVariant> = mutableListOf(),
    var runAllVariants: Boolean = false,
    var busyPatterns: String = "",
    var errorPatterns: String = "",
    var recoveryPatterns: String = ""
) {
    fun copyDeep(): JobDefinition = copy(variants = variants.map { it.copy() }.toMutableList())
}

data class StatusPreset(val errors: String, val recovery: String, val busy: String = "")

object JobPresets {
    fun forType(type: JobType): StatusPreset = when (type) {
        JobType.ANGULAR -> StatusPreset(
            errors = "Failed to compile\nApplication bundle generation failed\nregex:NG\\d+:\nregex:^ERROR",
            recovery = "Compiled successfully\nApplication bundle generation complete\nWatch mode enabled\nregex:Local:\\s+https?://",
            busy = "Changes detected. Rebuilding\nApplication bundle generation started\nGenerating browser application bundles\nBuilding...\nCompiling..."
        )
        JobType.VITE -> StatusPreset(
            errors = "Internal server error\nPre-transform error\nregex:^error",
            recovery = "ready in\npage reload\nregex:Local:\\s+https?://"
        )
        JobType.NODE -> StatusPreset(
            errors = "UnhandledPromiseRejection\nEADDRINUSE\nregex:^Error:",
            recovery = "Listening on\nServer running\nStarted"
        )
        JobType.TYPESCRIPT -> StatusPreset(
            errors = "Found 1 error\nregex:Found [2-9]\\d* errors\nregex:error TS\\d+:",
            recovery = "Found 0 errors\nWatching for file changes"
        )
        JobType.JEST -> StatusPreset(
            errors = "FAIL\nTest Suites:.*failed",
            recovery = "PASS\nTest Suites:.*passed"
        )
        JobType.DOCKER_COMPOSE -> StatusPreset(
            errors = "dependency failed to start\nunhealthy\npermission denied",
            recovery = "Started\nHealthy\nRunning"
        )
        JobType.SPRING_BOOT -> StatusPreset(
            errors = "APPLICATION FAILED TO START\nBeanCreationException\nregex:^ERROR",
            recovery = "Started .* in .* seconds\nTomcat started on port"
        )
        else -> StatusPreset("", "")
    }
}
