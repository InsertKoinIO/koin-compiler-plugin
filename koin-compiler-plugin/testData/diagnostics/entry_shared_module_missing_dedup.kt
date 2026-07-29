// RUN_PIPELINE_TILL: BACKEND
// KOIN-D001 dedup (1.1.0). AppModule is loaded from TWO separate startKoin { } entry points in
// this ONE compilation (a shared module reused by, say, a main entry and a preview/test entry).
// Service's missing Repo dependency is the SAME underlying miss both times full-graph validation
// reaches it — expect exactly ONE KOIN-D001, not one per entry point that reaches the module.
//
// This only became observable once A2 was removed: with A2 present, AppModule was already
// authoritatively validated during Phase 1 (before either startKoin runs), so neither entry
// point's A3 pass ever re-touched it — the duplication this test guards against couldn't happen
// yet. Confirmed via a RED-before-GREEN check: temporarily disabling the dedup guard reproduces
// two KOIN-D001 lines here.
// FILE: test.kt
package testpkg

import org.koin.core.context.startKoin
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

fun entryOne() {
    startKoin {
        modules(AppModule::class)
    }
}

fun entryTwo() {
    startKoin {
        modules(AppModule::class)
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, primaryConstructor, propertyDeclaration */
