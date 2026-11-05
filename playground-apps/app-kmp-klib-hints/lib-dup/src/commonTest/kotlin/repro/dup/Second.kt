package repro.dup

import org.koin.dsl.koinApplication
import org.koin.dsl.module

internal fun second() = koinApplication { modules(module { single<B> { BImpl() } }) }
