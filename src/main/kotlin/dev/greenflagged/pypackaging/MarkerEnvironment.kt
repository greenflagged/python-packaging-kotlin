package dev.greenflagged.pypackaging

/**
 * The values a [Marker] is evaluated against: the PEP 508 environment variables
 * (`python_version`, `sys_platform`, ...) plus `extra`, and the set-valued `extras`
 * and `dependency_groups` used by lock files.
 *
 * Python's `packaging` fills in the running interpreter's values by default. A JVM
 * has no Python interpreter to describe, so here every value must be supplied;
 * evaluating a marker that names a missing variable throws
 * [UndefinedEnvironmentNameException]. To evaluate "for any platform", build one
 * environment per target platform.
 *
 * ```kotlin
 * val env = MarkerEnvironment.builder()
 *     .pythonVersion("3.12").pythonFullVersion("3.12.4")
 *     .sysPlatform("linux").platformSystem("Linux").platformMachine("x86_64")
 *     .osName("posix").implementationName("cpython").implementationVersion("3.12.4")
 *     .platformPythonImplementation("CPython").platformRelease("6.8.0").platformVersion("")
 *     .build()
 * ```
 */
public class MarkerEnvironment private constructor(
    private val strings: Map<String, String>,
    private val sets: Map<String, Set<String>>,
) {
    /** The string-valued variables. */
    public val values: Map<String, String> get() = strings

    /** The set-valued variables (`extras`, `dependency_groups`). */
    public val setValues: Map<String, Set<String>> get() = sets

    internal fun lookup(name: String): Any? = strings[name] ?: sets[name]

    internal fun has(name: String): Boolean = name in strings || name in sets

    /** Returns a builder initialised with this environment's values. */
    public fun toBuilder(): Builder = Builder().also { b ->
        strings.forEach { (k, v) -> b.set(k, v) }
        sets.forEach { (k, v) -> b.setValues(k, v) }
    }

    override fun equals(other: Any?): Boolean =
        other is MarkerEnvironment && strings == other.strings && sets == other.sets

    override fun hashCode(): Int = strings.hashCode() * 31 + sets.hashCode()

    override fun toString(): String = "MarkerEnvironment(${strings + sets})"

    /** Builds a [MarkerEnvironment]. Setting a variable twice keeps the last value. */
    public class Builder internal constructor() {
        private val strings = LinkedHashMap<String, String>()
        private val sets = LinkedHashMap<String, Set<String>>()

        /** Sets a string-valued variable by its marker name. */
        public fun set(name: String, value: String): Builder = apply {
            sets.remove(name)
            strings[name] = value
        }

        /** Sets a set-valued variable by its marker name, such as `extras`. */
        public fun setValues(name: String, values: Set<String>): Builder = apply {
            strings.remove(name)
            sets[name] = values.toSet()
        }

        /** Sets every entry of [values] as a string-valued variable. */
        public fun setAll(values: Map<String, String>): Builder = apply { values.forEach { (k, v) -> set(k, v) } }

        /** `implementation_name`, e.g. `cpython`. */
        public fun implementationName(value: String): Builder = set("implementation_name", value)

        /** `implementation_version`, e.g. `3.12.4`. */
        public fun implementationVersion(value: String): Builder = set("implementation_version", value)

        /** `os_name`, e.g. `posix` or `nt`. */
        public fun osName(value: String): Builder = set("os_name", value)

        /** `platform_machine`, e.g. `x86_64`. */
        public fun platformMachine(value: String): Builder = set("platform_machine", value)

        /** `platform_release`, e.g. `6.8.0`. */
        public fun platformRelease(value: String): Builder = set("platform_release", value)

        /** `platform_system`, e.g. `Linux`, `Darwin` or `Windows`. */
        public fun platformSystem(value: String): Builder = set("platform_system", value)

        /** `platform_version`, the system's release version string. */
        public fun platformVersion(value: String): Builder = set("platform_version", value)

        /** `python_full_version`, e.g. `3.12.4`. */
        public fun pythonFullVersion(value: String): Builder = set("python_full_version", value)

        /** `platform_python_implementation`, e.g. `CPython`. */
        public fun platformPythonImplementation(value: String): Builder = set("platform_python_implementation", value)

        /** `python_version`, e.g. `3.12`. */
        public fun pythonVersion(value: String): Builder = set("python_version", value)

        /** `sys_platform`, e.g. `linux`, `darwin` or `win32`. */
        public fun sysPlatform(value: String): Builder = set("sys_platform", value)

        /** `extra`: the extra being evaluated for in metadata, or `""` for none. */
        public fun extra(value: String): Builder = set("extra", value)

        /** `extras`: the extras requested, for lock-file markers. */
        public fun extras(values: Set<String>): Builder = setValues("extras", values)

        /** `dependency_groups`: the dependency groups requested, for lock-file markers. */
        public fun dependencyGroups(values: Set<String>): Builder = setValues("dependency_groups", values)

        /** Builds the environment. */
        public fun build(): MarkerEnvironment =
            MarkerEnvironment(LinkedHashMap(strings), LinkedHashMap(sets))
    }

    public companion object {
        /** Starts building an environment. */
        @JvmStatic
        public fun builder(): Builder = Builder()

        /** An environment with the given string-valued variables. */
        @JvmStatic
        public fun of(values: Map<String, String>): MarkerEnvironment = Builder().setAll(values).build()
    }
}

/**
 * Where a marker comes from, which changes the variables that have defaults.
 * Python: `packaging.markers.EvaluateContext`.
 */
public enum class EvaluateContext {
    /** Core metadata (the default): `extra` defaults to `""`. */
    METADATA,

    /** Lock files: `extras` and `dependency_groups` default to empty sets. */
    LOCK_FILE,

    /** Any other requirement: no extra defaults. */
    REQUIREMENT,
}
