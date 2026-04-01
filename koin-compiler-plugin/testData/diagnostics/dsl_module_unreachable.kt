// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
import org.koin.dsl.module
import org.koin.core.context.startKoin
import org.koin.plugin.module.dsl.single

class Repository
class Service(val repo: Repository)
class Tracker

// trackerModule exists but is NOT loaded — error even though no one depends on Tracker
val trackerModule = module {
    single<Tracker>()
}

val coreModule = module {
    single<Repository>()
}

val appModule = module {
    includes(coreModule)
    single<Service>()
}

fun main() {
    startKoin {
        modules(appModule)
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, primaryConstructor, propertyDeclaration */
