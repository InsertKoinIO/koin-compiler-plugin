package org.koin.compiler.plugin

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.incremental.components.ExpectActualTracker
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.koin.compiler.adapter.KotlinAdapterLoader
import org.koin.compiler.plugin.ir.KoinIrExtension
import java.util.concurrent.atomic.AtomicInteger

/**
 * Centralized logger for the Koin compiler plugin.
 * Provides conditional logging based on user configuration.
 *
 * Two log levels:
 * - User logs: Component detection, DSL interceptions, annotations processed (enabled by userLogs=true)
 * - Debug logs: Internal plugin processing details (enabled by debugLogs=true)
 *
 * Performance: All logging functions are inline with lambda parameters.
 * When logging is disabled, the message lambda is never invoked, avoiding
 * string concatenation overhead at call sites.
 *
 * FIR extensions don't receive configuration directly, so we store it globally.
 */
/**
 * Severity for the Koin plugin's own informational/compatibility output — distinct from
 * [KoinDiagnostic.Severity], which governs real, actionable diagnostics (KOIN-Dxxx/Wxxx/etc.)
 * and is never affected by this setting.
 *
 * WARNING preserves today's behavior (visible in default Gradle output). INFO is safe under
 * `allWarningsAsErrors` / `-Werror` — confirmed via `CompilerMessageSeverity.isRegularWarning()`,
 * which only treats WARNING and STRONG_WARNING as build-failing under that flag.
 */
enum class KoinLogSeverity {
    WARNING, INFO;

    companion object {
        fun parse(raw: String?): KoinLogSeverity =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: WARNING
    }
}

object KoinPluginLogger {
    /**
     * Per-compilation message collector storage.
     *
     * Was `@Volatile var messageCollector: MessageCollector`. Under K2's parallel-daemon mode
     * multiple compilations can share a daemon, and each one's `init()` writes through this
     * singleton. With a single volatile field, compilation B's `init()` could overwrite A's
     * collector while A was still mid-IR-generation — A's subsequent diagnostics would land in
     * B's output stream (wrong build's log).
     *
     * `InheritableThreadLocal` scopes the collector to the compilation's entry thread and any
     * threads it spawns (IR generation occasionally fans out). The volatile `fallbackCollector`
     * preserves the original behavior for callers reached without a per-thread context — bare
     * CLI, unit tests, and the legacy alias in [KoinPluginMessageCollector].
     *
     * Read order in [effectiveCollector]: thread-local first, fallback second.
     */
    @PublishedApi
    internal val threadCollector: InheritableThreadLocal<MessageCollector?> =
        InheritableThreadLocal()

    @Volatile
    @PublishedApi
    internal var fallbackCollector: MessageCollector = MessageCollector.NONE

    @PublishedApi
    internal val effectiveCollector: MessageCollector
        get() = threadCollector.get() ?: fallbackCollector

    /**
     * Per-compilation config flags.
     *
     * Same daemon-parallel hazard as [threadCollector]: multiple compilations share a daemon JVM
     * and each `init()` writes these. When they were plain global `@Volatile var`s, one
     * compilation's flags could leak into another (wrong-build behavior) — and, because the test
     * suite runs many compilations in one JVM with IR fan-out threads, it made the suite
     * order-dependent / flaky (KTZ-4414). Held per-thread (InheritableThreadLocal, inherited by
     * spawned IR threads) with a volatile fallback for callers reached without a per-thread
     * context (bare CLI, unit tests). Read order: thread-local first, fallback second.
     */
    private data class Flags(
        val userLogs: Boolean = false,
        val debugLogs: Boolean = false,
        val unsafeDslChecks: Boolean = true,
        val skipDefaultValues: Boolean = true,
        val compileSafety: Boolean = true,
        val aiAssist: Boolean = false,
        val moduleId: String? = null,
        val lookupTracker: LookupTracker? = null,
        val logSeverity: KoinLogSeverity = KoinLogSeverity.WARNING,
        val versionCheckSeverity: KoinLogSeverity = KoinLogSeverity.WARNING,
    )

    private val threadFlags: InheritableThreadLocal<Flags?> = InheritableThreadLocal()

    @Volatile
    private var fallbackFlags: Flags = Flags()

    private val effectiveFlags: Flags
        get() = threadFlags.get() ?: fallbackFlags

    val userLogsEnabled: Boolean get() = effectiveFlags.userLogs
    val debugLogsEnabled: Boolean get() = effectiveFlags.debugLogs
    val unsafeDslChecksEnabled: Boolean get() = effectiveFlags.unsafeDslChecks
    val skipDefaultValuesEnabled: Boolean get() = effectiveFlags.skipDefaultValues
    val compileSafetyEnabled: Boolean get() = effectiveFlags.compileSafety
    val aiAssistEnabled: Boolean get() = effectiveFlags.aiAssist

    /**
     * Compiler severity to use for [user]/[debug]/[userFir]/[debugFir]/[warn] — the plugin's
     * "here's what I'm doing" output, as opposed to real diagnostics ([report]) or the
     * Kotlin-version compatibility check ([effectiveVersionCheckCompilerSeverity]), which have
     * their own, separate severity setting.
     */
    @PublishedApi
    internal val effectiveLogCompilerSeverity: CompilerMessageSeverity
        get() = if (effectiveFlags.logSeverity == KoinLogSeverity.INFO) CompilerMessageSeverity.INFO else CompilerMessageSeverity.WARNING

    /**
     * Compiler severity for the Kotlin-version-compatibility gate. Deliberately a separate
     * setting from [effectiveLogCompilerSeverity]: a user muting informational plugin noise
     * should not also lose visibility into "you're on an unverified Kotlin version."
     */
    val effectiveVersionCheckCompilerSeverity: CompilerMessageSeverity
        get() = if (effectiveFlags.versionCheckSeverity == KoinLogSeverity.INFO) CompilerMessageSeverity.INFO else CompilerMessageSeverity.STRONG_WARNING

    /**
     * Gradle-module-unique identifier (typically `project.path`, e.g. `:featureA:ui`) used as the
     * leading segment of synthetic hint file names. Null when running outside Gradle (bare CLI,
     * tests without the Gradle plugin); generators fall back to FIR module-data name in that case.
     */
    val moduleId: String? get() = effectiveFlags.moduleId

    /** LookupTracker from compiler configuration, for direct IC lookup recording. */
    val lookupTracker: LookupTracker? get() = effectiveFlags.lookupTracker

    /**
     * Highest severity of any diagnostic reported via [report] during this compilation.
     * Used by [flushAiAssistCta] to decide whether to emit a trailing CTA and at what severity.
     * 0 = none, 1 = warning, 2 = error.
     *
     * AtomicInteger (not @Volatile Int) so concurrent `report()` calls cannot lose a
     * higher-severity update via read-then-write — `updateAndGet` is the only correct
     * primitive for a monotonic max accumulator.
     */
    private val highestDiagnosticSeverity: AtomicInteger = AtomicInteger(0)

    /**
     * Location of the last located diagnostic reported via [report]. Attached to the
     * trailing AI-assist CTA so it shares a per-file output bucket with the error,
     * preventing renderers (Gradle's K2 collector) from sorting the location-less CTA
     * ahead of file-anchored messages.
     *
     * Per-thread (InheritableThreadLocal) for the same daemon-parallel reason as
     * [threadCollector] — two compilations would otherwise stomp each other's anchor.
     */
    private val lastDiagnosticLocation: InheritableThreadLocal<CompilerMessageLocation?> =
        InheritableThreadLocal()

    /**
     * Bind [collector] to the current thread's slot for the duration of one compilation phase
     * (typically `IrGenerationExtension.generate`). Use a try/finally with [unbindThreadCollector]
     * to ensure it's cleared even on exceptions.
     *
     * Needed because Gradle daemon worker pools can dispatch a compilation's IR phase on a
     * different thread than the one that called [init]; without a re-bind, the per-thread
     * collector slot would be empty and reads would fall through to [fallbackCollector]
     * (which a parallel compilation may have overwritten).
     */
    fun bindThreadCollector(collector: MessageCollector) {
        threadCollector.set(collector)
    }

    fun unbindThreadCollector() {
        threadCollector.remove()
    }

    /**
     * Capture the current compilation's config flags as an opaque handle, on the thread that
     * called [init] (typically `registerExtensions`). Re-bound via [bindThreadFlags] at the start
     * of the IR phase — the same daemon-parallel rebind the collector needs (see
     * [bindThreadCollector]): `InheritableThreadLocal` only inherits at thread creation, so a pool
     * worker running IR generation later would otherwise fall through to the shared [fallbackFlags]
     * that a parallel compilation may have overwritten. This is what left `compileSafety` /
     * `skipDefaultValues` reads racy during IR codegen (KTZ-4414).
     */
    fun captureFlags(): Any = effectiveFlags

    fun bindThreadFlags(handle: Any) {
        (handle as? Flags)?.let { threadFlags.set(it) }
    }

    fun unbindThreadFlags() {
        threadFlags.remove()
    }

    /**
     * Initialize the logger with configuration from the compiler.
     *
     * Writes the collector to both the per-thread slot (so this compilation's diagnostics
     * stay scoped) and to the volatile fallback (for callers reached outside the compilation
     * thread group — primarily the legacy [KoinPluginMessageCollector] alias).
     */
    fun init(collector: MessageCollector, userLogs: Boolean, debugLogs: Boolean, unsafeDslChecks: Boolean = true, skipDefaultValues: Boolean = true, compileSafety: Boolean = true, aiAssist: Boolean = true, moduleId: String? = null, lookupTracker: LookupTracker? = null, logSeverity: KoinLogSeverity = KoinLogSeverity.WARNING, versionCheckSeverity: KoinLogSeverity = KoinLogSeverity.WARNING) {
        threadCollector.set(collector)
        fallbackCollector = collector
        val flags = Flags(
            userLogs = userLogs,
            debugLogs = debugLogs,
            unsafeDslChecks = unsafeDslChecks,
            skipDefaultValues = skipDefaultValues,
            compileSafety = compileSafety,
            aiAssist = aiAssist,
            moduleId = moduleId?.takeIf { it.isNotBlank() },
            lookupTracker = lookupTracker,
            logSeverity = logSeverity,
            versionCheckSeverity = versionCheckSeverity,
        )
        threadFlags.set(flags)
        fallbackFlags = flags
        highestDiagnosticSeverity.set(0)
        lastDiagnosticLocation.remove()
    }

    /**
     * Log a user-facing message.
     * These are high-level messages about what the plugin is doing:
     * - DSL interceptions (single<T>(), factory<T>(), etc.)
     * - Annotations detected (@Singleton, @Factory, @Module)
     * - Generated modules and their contents
     *
     * The message lambda is only invoked if logging is enabled.
     */
    inline fun user(message: () -> String) {
        if (userLogsEnabled) {
            effectiveCollector.report(effectiveLogCompilerSeverity, "[Koin] ${message()}")
        }
    }

    /**
     * Log a debug message.
     * These are detailed internal processing messages for debugging:
     * - FIR phase details
     * - IR transformation internals
     * - Discovery and registration steps
     *
     * The message lambda is only invoked if logging is enabled.
     */
    inline fun debug(message: () -> String) {
        if (debugLogsEnabled) {
            effectiveCollector.report(effectiveLogCompilerSeverity, "[Koin-Debug] ${message()}")
        }
    }

    /**
     * Log a message that is always emitted regardless of userLogs/debugLogs settings.
     *
     * Despite the name, this is NOT "never-silenceable, critical" material — its only current
     * caller ([org.koin.compiler.plugin.ir.KoinMonitorTransformer.logSummary]) is a "@Monitor
     * tracing enabled" feature-status summary, the same class of output as [user]. It obeys the
     * same severity setting so it doesn't reintroduce #73 on its own.
     */
    fun warn(message: String) {
        effectiveCollector.report(effectiveLogCompilerSeverity, "[Koin] $message")
    }

    /**
     * Log a user-facing message in FIR phase.
     * Adds [FIR] prefix to distinguish phase.
     *
     * The message lambda is only invoked if logging is enabled.
     */
    inline fun userFir(message: () -> String) {
        if (userLogsEnabled) {
            effectiveCollector.report(effectiveLogCompilerSeverity, "[Koin-FIR] ${message()}")
        }
    }

    /**
     * Log a debug message in FIR phase.
     * Adds [FIR] prefix to distinguish phase.
     *
     * The message lambda is only invoked if logging is enabled.
     */
    inline fun debugFir(message: () -> String) {
        if (debugLogsEnabled) {
            effectiveCollector.report(effectiveLogCompilerSeverity, "[Koin-Debug-FIR] ${message()}")
        }
    }

    /**
     * Report a compilation error.
     * This will cause compilation to fail.
     */
    fun error(message: String) {
        effectiveCollector.report(CompilerMessageSeverity.ERROR, "[Koin] $message")
    }

    /**
     * Report a compilation error with source location.
     * The error message will include file path and line number.
     */
    fun error(message: String, filePath: String?, line: Int, column: Int) {
        val location = if (filePath != null) {
            CompilerMessageLocation.create(filePath, line, column, null)
        } else null
        effectiveCollector.report(CompilerMessageSeverity.ERROR, "[Koin] $message", location)
    }

    /**
     * Report a typed Koin diagnostic.
     *
     * Format: `[Koin][CODE] <message>`. The AI-assist CTA is emitted once at the tail
     * of the compilation by [flushAiAssistCta], not per diagnostic.
     */
    fun report(diagnostic: KoinDiagnostic, filePath: String? = null, line: Int = -1, column: Int = -1) {
        val body = "[Koin][${diagnostic.code}] ${diagnostic.message}"
        val severity = when (diagnostic.severity) {
            KoinDiagnostic.Severity.ERROR -> CompilerMessageSeverity.ERROR
            KoinDiagnostic.Severity.WARNING -> CompilerMessageSeverity.WARNING
        }
        val severityRank = if (diagnostic.severity == KoinDiagnostic.Severity.ERROR) 2 else 1
        highestDiagnosticSeverity.updateAndGet { current -> if (severityRank > current) severityRank else current }
        val location = if (filePath != null && line >= 0) {
            CompilerMessageLocation.create(filePath, line, column.coerceAtLeast(0), null)
        } else null
        if (location != null) lastDiagnosticLocation.set(location)
        effectiveCollector.report(severity, body, location)
    }

    /**
     * Emit a single trailing CTA pointing developers to the Kotzilla MCP Server.
     * No-op unless [aiAssistEnabled] is on and at least one diagnostic was reported
     * during the current compilation. Emitted at the highest severity seen so it
     * sorts with the other Koin messages in the build log.
     *
     * Caller passes its own captured [collector] to avoid a daemon-parallel race where
     * another compilation's [init] swaps the singleton's collector mid-flight and the CTA
     * lands in the wrong task's output stream. The location of the last located diagnostic
     * is attached so the CTA shares a per-file bucket with the error in Gradle's K2
     * renderer, preventing it from sorting ahead of file-anchored messages.
     *
     * Called once per compilation by [org.koin.compiler.plugin.ir.KoinIrExtension] at
     * the tail of IR processing.
     */
    fun flushAiAssistCta(collector: MessageCollector = effectiveCollector) {
        val rank = highestDiagnosticSeverity.get()
        if (!aiAssistEnabled || rank == 0) return
        val severity = if (rank >= 2) CompilerMessageSeverity.ERROR else CompilerMessageSeverity.WARNING
        collector.report(
            severity,
            "[Koin] → Fix with AI: set up Kotzilla MCP at ${KoinPluginConstants.AI_ASSIST_CTA_URL}",
            lastDiagnosticLocation.get(),
        )
        highestDiagnosticSeverity.set(0)
        lastDiagnosticLocation.remove()
    }
}

// Legacy alias for backward compatibility with FIR code
@Deprecated("Use KoinPluginLogger instead", ReplaceWith("KoinPluginLogger"))
object KoinPluginMessageCollector {
    fun log(message: String) {
        KoinPluginLogger.debugFir { message }
    }
}

class KoinPluginComponentRegistrar: CompilerPluginRegistrar() {
    override val supportsK2: Boolean
        get() = true

    override val pluginId: String
        get() = "io.insert-koin.compiler.plugin"

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        val messageCollector = configuration.get(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
        val userLogs = configuration.get(KoinConfigurationKeys.USER_LOGS, false)
        val debugLogs = configuration.get(KoinConfigurationKeys.DEBUG_LOGS, false)
        val unsafeDslChecks = configuration.get(KoinConfigurationKeys.UNSAFE_DSL_CHECKS, true)
        val skipDefaultValues = configuration.get(KoinConfigurationKeys.SKIP_DEFAULT_VALUES, true)
        val compileSafety = configuration.get(KoinConfigurationKeys.COMPILE_SAFETY, true)
        val aiAssist = configuration.get(KoinConfigurationKeys.AI_ASSIST, true)
        val moduleId = configuration.get(KoinConfigurationKeys.MODULE_ID)
        val logSeverity = KoinLogSeverity.parse(configuration.get(KoinConfigurationKeys.LOG_SEVERITY))
        val versionCheckSeverity = KoinLogSeverity.parse(configuration.get(KoinConfigurationKeys.VERSION_CHECK_SEVERITY))

        // IC trackers for incremental compilation support
        val lookupTracker = configuration.get(CommonConfigurationKeys.LOOKUP_TRACKER)

        // Initialize the centralized logger (includes lookupTracker for FIR-level IC recording)
        KoinPluginLogger.init(messageCollector, userLogs, debugLogs, unsafeDslChecks, skipDefaultValues, compileSafety, aiAssist, moduleId, lookupTracker, logSeverity, versionCheckSeverity)
        val expectActualTracker = configuration.get(
            CommonConfigurationKeys.EXPECT_ACTUAL_TRACKER,
            ExpectActualTracker.DoNothing
        )
        
        // Extension registration binds to compiler internals whose binary contract
        // moves across Kotlin versions — route it through the version adapter
        // matching the running compiler (see koin-compiler-version-adapter/).
        val selection = KotlinAdapterLoader.load()
        selection.warnings.forEach {
            messageCollector.report(KoinPluginLogger.effectiveVersionCheckCompilerSeverity, it)
        }
        val adapter = selection.adapter ?: run {
            messageCollector.report(CompilerMessageSeverity.ERROR, selection.error ?: "Koin compiler plugin: no compatible Kotlin version adapter")
            return
        }
        KoinPluginLogger.debug { "Kotlin version adapter: ${adapter.baselineKotlin} (compiler ${org.jetbrains.kotlin.config.KotlinCompilerVersion.VERSION})" }

        // FIR extension for generating visible declarations (module extension property);
        // IR extension for transforming function bodies — captures messageCollector for the
        // trailing AI-assist CTA so a parallel daemon compilation can't redirect its output.
        adapter.registerCompilerExtensions(
            this,
            KoinPluginRegistrar(),
            KoinIrExtension(lookupTracker, expectActualTracker, messageCollector, KoinPluginLogger.captureFlags()),
        )
    }
}
