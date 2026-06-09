package org.koin.compiler.plugin.ir

import org.jetbrains.kotlin.KtIoFileSourceFile
import org.jetbrains.kotlin.KtSourceFile
import java.io.File

/**
 * Source file attached to the synthetic FIR files the plugin generates for hints.
 *
 * KLIB metadata serialization walks every file in the module and resolves it to an
 * io [File] (`serializeModuleIntoKlib` → `KtSourceFile.toIoFileOrNull()`). A synthetic
 * file with no `sourceFile` resolves to `null`, which the Native backend tolerates but
 * the JS/Wasm serializer rejects with `IllegalStateException: No file found for source
 * null` (KT-82395). Anchoring the file on a [KtIoFileSourceFile] — even a path that is
 * not a real on-disk file — gives the serializer a non-null io File and keeps wasm/js
 * builds working.
 *
 * [path] should be the hint file's own deterministic path (the same value used for the
 * IR `fileEntry`), so the FIR source and IR file entry agree.
 */
internal fun syntheticHintSourceFile(path: String): KtSourceFile = KtIoFileSourceFile(File(path))
