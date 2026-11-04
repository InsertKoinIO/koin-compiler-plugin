package repro.dsl

import org.koin.core.module.Module
import org.koin.dsl.module

// #113: classic DSL definition in a dependency module.
interface LoggerService

internal object RealLogger : LoggerService

fun libDslModule(): Module = module {
    single<LoggerService> { RealLogger }
}
