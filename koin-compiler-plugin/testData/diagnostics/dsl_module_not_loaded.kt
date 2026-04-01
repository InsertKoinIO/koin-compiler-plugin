// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
import org.koin.dsl.module
import org.koin.core.context.startKoin
import org.koin.plugin.module.dsl.single

class Repository
class Service(val repo: Repository)

// Repository is defined here but this module is NOT loaded
val coreModule = module {
    single<Repository>()
}

val appModule = module {
    single<Service>()
}

fun main() {
    // Only appModule is loaded — coreModule is NOT loaded and NOT included
    // Service depends on Repository which is unreachable → error
    startKoin {
        modules(appModule)
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, primaryConstructor, propertyDeclaration */
