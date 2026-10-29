// RUN_PIPELINE_TILL: BACKEND
// GH #115 — cross-module DSL hint drops per-parameter flags.
//
// List<T> parameter — resolved with getAll(), empty when nothing is bound.
//
// The DSL definition below validates SILENTLY in its own module (lib has no entry point, but the
// same shape inside one module is accepted). Seen from app, it is rebuilt from lib's dsl_ hint,
// whose requirements are decoded with every flag hard-coded false (DslHintGenerator), so the
// parameter becomes a hard requirement → FALSE KOIN-D001 at app's entry point.
// Real-world shape: nowinandroid TopicViewModel(@InjectedParam topicId: String), consumed by :app.
//
// EXPECTED: SILENT.

// MODULE: lib
// FILE: lib/Lib.kt
package lib

import org.koin.dsl.module
import org.koin.plugin.module.dsl.single


interface Plugin
class Service(val plugins: List<Plugin>)

val libModule = module {
    single<Service>()
}

// MODULE: app(lib)
// FILE: app/App.kt
package app

import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single
import lib.libModule

class AppThing

val appModule = module {
    includes(libModule)
    single<AppThing>()
}

fun main() {
    startKoin {
        modules(appModule)
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, interfaceDeclaration, lambdaLiteral, primaryConstructor,
propertyDeclaration */
