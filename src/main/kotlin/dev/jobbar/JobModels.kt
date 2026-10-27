package dev.jobbar

import com.intellij.openapi.util.text.NaturalComparator
import com.intellij.util.xmlb.annotations.Transient
import java.util.UUID

enum class JobRunMode(val label: String) {
    ONE_SHOT("One-shot command"),
    TRACKED("Tracked process"),
    // A launcher ("group" in code) starts other jobs (all at once or one after another); no command of its own.
    GROUP("Launcher");

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

enum class VariantSortOrder(val label: String) {
    LABEL_ASC("Label A → Z"),
    LABEL_DESC("Label Z → A"),
    VALUE_ASC("Value A → Z"),
    VALUE_DESC("Value Z → A");

    fun comparator(): Comparator<JobVariant> {
        val natural = NaturalComparator.INSTANCE
        return when (this) {
            LABEL_ASC -> compareBy(natural) { it.label }
            LABEL_DESC -> compareByDescending(natural) { it.label }
            VALUE_ASC -> compareBy(natural) { it.value }
            VALUE_DESC -> compareByDescending(natural) { it.value }
        }
    }

    override fun toString(): String = label
}

data class JobVariant(
    var label: String = "",
    var value: String = ""
)

/** A group member: a job id plus an optional variant value ("" = none, [ALL_VARIANTS] = run all variants). */
data class GroupMember(
    var jobId: String = "",
    var variant: String = ""
) {
    companion object {
        const val ALL_VARIANTS = "*"
    }
}

data class JobDefinition(
    var id: String = UUID.randomUUID().toString(),
    var name: String = "New job",
    // Compact view: label shown instead of the name.
    var shortName: String = "",
    // Optional icon key from JobIcons; with an icon the status shows as a dot on it, and the text can be hidden.
    var icon: String = "",
    var iconOnly: Boolean = false,
    var command: String = "",
    var workingDirectory: String = "\$PROJECT_DIR\$",
    var runMode: JobRunMode = JobRunMode.ONE_SHOT,
    var type: JobType = JobType.GENERIC,
    var reuseConsole: Boolean = true,
    var parameterName: String = "",
    var variants: MutableList<JobVariant> = mutableListOf(),
    var runAllVariants: Boolean = false,
    // Variants keep their custom order unless auto-sort is on; the order is the last one picked.
    var variantSortOrder: VariantSortOrder = VariantSortOrder.LABEL_ASC,
    var autoSortVariants: Boolean = false,
    var busyPatterns: String = "",
    var errorPatterns: String = "",
    var recoveryPatterns: String = "",
    // Disabled jobs stay in the list but are hidden from toolbars and shortcuts and cannot run.
    var enabled: Boolean = true,
    // Tracked jobs: also ready once this local port accepts connections / this URL responds (0 / "" = off).
    var readyPort: Int = 0,
    var readyUrl: String = "",
    // Groups only.
    var members: MutableList<GroupMember> = mutableListOf(),
    var groupSequential: Boolean = true
) {
    fun copyDeep(): JobDefinition = copy(
        variants = variants.map { it.copy() }.toMutableList(),
        members = members.map { it.copy() }.toMutableList()
    )

    @get:Transient
    val isGroup: Boolean get() = runMode == JobRunMode.GROUP

    @get:Transient
    val hasReadyCheck: Boolean get() = readyPort > 0 || readyUrl.isNotBlank()

    @get:Transient
    val compactLabel: String get() = shortName.ifBlank { name }
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
