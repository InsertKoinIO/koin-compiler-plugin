// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// Negative case for function-returned-module resolution: includes() called through virtual
// dispatch (an interface member) must NOT be resolved to a phantom id — an override's fqName
// doesn't identify which body actually runs. It must fall back to "unresolvable", same as any
// other unreadable includes() argument: KOIN-W003 disclosed, no false KOIN-D001/D002/W001 for a
// graph that is valid at runtime (provider.provide() really does return coreModule).
package testpkg

import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Repository
class Service(val repo: Repository)

val coreModule = module {
    single<Repository>()
}

interface ModuleProvider {
    fun provide(): Module
}

class RealProvider : ModuleProvider {
    override fun provide() = coreModule
}

val provider: ModuleProvider = RealProvider()

fun appModule() = module {
    includes(provider.provide())
    single<Service>()
}

fun useIt() {
    val koin = koinApplication {
        modules(appModule())
    }.koin
    val repo = koin.get<Repository>()
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, interfaceDeclaration, lambdaLiteral, localProperty,
override, primaryConstructor, propertyDeclaration */
