// RUN_PIPELINE_TILL: BACKEND
// A3 RESHAPE — baseline matrix. Entry point = REAL `koinApplication { modules(K::class) }`
// (org.koin.dsl.koinApplication), loading an ANNOTATION @ComponentScan module.
//
// Service (scanned @Singleton) needs Repo; Repo is provided by no definition. Genuine miss.
//
// TARGET: KOIN-D001 Missing dependency: Repo.
// PROBE: real `org.koin.dsl.koinApplication` is NOT in the entry-point recognition set at all
// (KoinStartTransformer.kt:126-137 lists startKoin variants + koinConfiguration, not koinApplication)
// — records whether this common root is verified or silently unchecked.
// FILE: test.kt
package testpkg

import org.koin.dsl.koinApplication
import org.koin.plugin.module.dsl.modules
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Singleton

class Repo

@Singleton
class Service(val repo: Repo)

@Module
@ComponentScan
class AppModule

fun main() {
    koinApplication {
        modules(AppModule::class)
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, primaryConstructor, propertyDeclaration */
