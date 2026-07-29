// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// Companion to dsl_bind_missing_dependency_d001: `bind` on a definition type other than `single`.
//
// All the DSL definition builders funnel through the same `collectBindType`, which rebuilds the
// definition with `copy()` — and copy() resets the body-held `requirements`/`origin`. The sibling
// test covers `single`; `factory` is worth pinning separately because its primary constructor is a
// different shape, and a future refactor could plausibly retain metadata on one path and not the
// other.
//
// Note on chaining: `bind A::class bind B::class` does NOT type-check against this Koin version
// (the first `bind` does not return something the second accepts), so there is no
// copy()-of-a-copy path to guard — the concern is moot rather than untested.
//
// EXPECTED: KOIN-D001 for MissingDep, attributed to the factory definition.
package testpkg

import org.koin.dsl.bind
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.factory

interface Alpha
class MissingDep
class FactoryImpl(val dep: MissingDep) : Alpha

val appModule = module {
    factory<FactoryImpl>() bind Alpha::class
}

fun useIt() {
    koinApplication { modules(appModule) }
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, functionDeclaration, interfaceDeclaration,
   lambdaLiteral, primaryConstructor, propertyDeclaration, topLevelPropertyDeclaration */
