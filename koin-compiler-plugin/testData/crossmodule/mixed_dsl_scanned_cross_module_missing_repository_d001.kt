// The loaded library DSL module provides Repository's transitive Clock dependency. The app
// separately loads a scanned annotation module whose Scanned class depends on Repository.

// MODULE: lib
// FILE: lib/Bindings.kt
package repro.lib

import org.koin.dsl.module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.plugin.module.dsl.factory
import org.koin.plugin.module.dsl.single

class Clock
class Repository(val clock: Clock)
class Scanned @javax.inject.Inject constructor(val repository: Repository)

@Module
@ComponentScan("repro.lib")
class ComponentsModule

val bindings = module {
    single<Clock>()
}

// MODULE: app(lib)
// FILE: app/App.kt
package repro.app

import org.koin.core.context.startKoin
import org.koin.plugin.module.dsl.modules
import repro.lib.ComponentsModule
import repro.lib.bindings

fun start() = startKoin {
    modules(ComponentsModule::class)
}

/* GENERATED_FIR_TAGS: functionDeclaration, lambdaLiteral */
