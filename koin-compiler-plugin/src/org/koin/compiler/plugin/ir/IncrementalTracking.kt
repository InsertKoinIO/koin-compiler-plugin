/*
 * Adapted from Metro's ic.kt (https://github.com/ZacSweers/metro), Apache 2.0 licensed,
 * Copyright (C) Zac Sweers. In particular, the `LookupTracker`/`ExpectActualTracker`
 * wiring and the use of `ExpectActualTracker` as a cross-file structural-change signal
 * for Kotlin incremental compilation originate there.
 */
package org.koin.compiler.plugin.ir

import org.jetbrains.kotlin.incremental.components.ExpectActualTracker
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.incremental.components.Position
import org.jetbrains.kotlin.incremental.components.ScopeKind
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.name.FqName
import org.koin.compiler.plugin.KoinPluginLogger
import java.io.File

/**
 * Incremental compilation tracking helpers for the Koin compiler plugin.
 *
 * Uses [LookupTracker] and [ExpectActualTracker] to inform Kotlin IC about
 * dependencies between files and declarations, so that annotation changes
 * trigger proper recompilation without needing --rerun-tasks.
 *
 * KMP-compatible — avoids JVM-backend-specific APIs (getIoFile, getKtFile).
 * Uses [IrFile.fileEntry.name] for file paths on all platforms.
 */

/**
 * Records a lookup dependency: the [callingFile] depends on [calleeClass].
 * If [calleeClass] changes, the calling file will be recompiled.
 */
internal fun trackClassLookup(
    lookupTracker: LookupTracker?,
    callingFile: IrFile?,
    calleeClass: IrClass
) {
    if (lookupTracker == null || callingFile == null) return
    val classId = calleeClass.classId ?: return
    val container = classId.outerClassId?.asSingleFqName() ?: classId.packageFqName
    val name = classId.shortClassName.asString()
    KoinPluginLogger.debug { "IC: trackClassLookup ${callingFile.fileEntry.name.substringAfterLast('/')} -> ${calleeClass.fqNameWhenAvailable}" }
    trackLookup(lookupTracker, callingFile, container, name)
}

/**
 * Records a lookup dependency on a specific declaration name within a container.
 */
internal fun trackLookup(
    lookupTracker: LookupTracker?,
    callingFile: IrFile?,
    container: FqName,
    declarationName: String
) {
    if (lookupTracker == null || callingFile == null) return
    val filePath = callingFile.fileEntry.name
    withLookupTracker(lookupTracker) {
        record(
            filePath = filePath,
            position = Position.NO_POSITION,
            scopeFqName = container.asString(),
            scopeKind = ScopeKind.PACKAGE,
            name = declarationName,
        )
    }
}

/**
 * Links two files for IC: if [calleeClass]'s file changes structurally,
 * [callingFile] will be recompiled. This catches additions of new declarations
 * that [LookupTracker] can't see (since they didn't exist before).
 */
internal fun linkDeclarationsForIC(
    expectActualTracker: ExpectActualTracker?,
    callingFile: IrFile?,
    calleeClass: IrClass
) {
    if (expectActualTracker == null || callingFile == null) return
    val calleeFile = calleeClass.fileOrNull ?: return
    val expectedPath = calleeFile.fileEntry.name
    val actualPath = callingFile.fileEntry.name
    if (expectedPath == actualPath) return
    KoinPluginLogger.debug { "IC: linkDeclarations ${actualPath.substringAfterLast('/')} <-> ${expectedPath.substringAfterLast('/')}" }
    expectActualTracker.report(
        expectedFile = File(expectedPath),
        actualFile = File(actualPath)
    )
}

/**
 * Null-safe, synchronized wrapper for [LookupTracker] operations.
 */
internal inline fun withLookupTracker(
    lookupTracker: LookupTracker?,
    body: LookupTracker.() -> Unit
) {
    if (lookupTracker != null) {
        synchronized(lookupTracker) { lookupTracker.body() }
    }
}
