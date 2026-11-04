// RUN_PIPELINE_TILL: BACKEND
// GH #124 guard: a preview-style `koinApplication { modules(module { }) }` in a library registers
// CV11 for that isolated graph only. It must not count as a provider for the app's entry point.
//
// EXPECTED: KOIN-D001 for CV11 in the app.

// MODULE: lib
// FILE: lib/Lib.kt
package lib

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.dsl.module
import org.koin.dsl.koinApplication
import org.koin.plugin.module.dsl.single

@Module
@Configuration
@ComponentScan
class LibModule

class CV11

class Other

@Single
class CV1(val cv11: CV11)

val otherKoin = module {
    single<Other>()
}

fun previewKoin() = koinApplication {
    modules(otherKoin, module { single<CV11>() })
}

// MODULE: app(lib)
// FILE: app/App.kt
package app

import lib.otherKoin
import org.koin.core.annotation.KoinApplication
import org.koin.plugin.module.dsl.startKoin

@KoinApplication
object MyApp

fun main() {
    startKoin<MyApp> {
        modules(otherKoin)
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, objectDeclaration, primaryConstructor,
propertyDeclaration */
