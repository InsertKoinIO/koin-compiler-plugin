// RUN_PIPELINE_TILL: BACKEND
// A KOIN-D001 on a definition from another Gradle module has no source location of its own, so the
// error has to say which entry point of THIS compilation loads it, and through which includes() chain.
// Without that, a compilation with several entry points (a test source set) gives no way to find the
// graph to fix.
//
// Topology:
//   kernel  : val kernelModule = module { single<SentNotifications>() }    SentNotifications needs Clock (nowhere)
//   feature : fun toolsModule() = module { includes(useCasesModule()) }
//             fun useCasesModule() = module { includes(kernelModule) }
//   app     : ToolsTest.kt  — koinApplication { modules(toolsModule(), module { single { Database() } }) }
//             OtherTest.kt  — an unrelated file of the same compilation
//
// EXPECTED: KOIN-D001 for kernel.Clock, loaded by the modules() call in ToolsTest.kt through
// toolsModule → useCasesModule → kernelModule.

// MODULE: kernel
// FILE: kernel/Kernel.kt
package kernel

import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Clock
class SentNotifications(val clock: Clock)

val kernelModule = module {
    single<SentNotifications>()
}

// MODULE: feature(kernel)
// FILE: feature/Feature.kt
package feature

import kernel.kernelModule
import org.koin.dsl.module

fun toolsModule() = module { includes(useCasesModule()) }

fun useCasesModule() = module { includes(kernelModule) }

// MODULE: app(feature, kernel)
// FILE: app/OtherTest.kt
package app

class Unrelated

fun other() = Unrelated()

// FILE: app/ToolsTest.kt
package app

import feature.toolsModule
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class Database

fun tools() = koinApplication {
    modules(
        toolsModule(),
        module { single { Database() } },
    )
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, primaryConstructor, propertyDeclaration */
