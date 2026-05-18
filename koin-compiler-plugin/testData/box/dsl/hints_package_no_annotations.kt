// FILE: test.kt
// Regression test for KTZ-4036 / GH koin-compiler-plugin#35:
// "Module doesn't contain package org.koin.plugin.hints" Fir2Ir crash on Kotlin 2.3.20.
//
// Trigger: a module that uses ONLY the Koin DSL — no @Module, no @ComponentScan,
// no @Singleton / @Factory annotations. getTopLevelCallableIds() still emits the
// default-label hint callable in HINTS_PACKAGE (so cross-module discovery works),
// but hasPackage() must claim the package unconditionally — K2.3.20's Fir2IrConverter
// throws IllegalStateException otherwise.
//
// Fixed by: hasPackage(HINTS_PACKAGE) returns true unconditionally
// in KoinModuleFirGenerator.kt.
import org.koin.dsl.module
import org.koin.dsl.koinApplication
import org.koin.plugin.module.dsl.single
import org.koin.plugin.module.dsl.factory

class Repo
class Service(val repo: Repo)

fun box(): String {
    val m = module {
        single<Repo>()
        factory<Service>()
    }
    val koin = koinApplication { modules(m) }.koin

    val r1 = koin.get<Repo>()
    val r2 = koin.get<Repo>()
    val s = koin.get<Service>()

    return if (r1 === r2 && s.repo === r1) "OK" else "FAIL"
}
