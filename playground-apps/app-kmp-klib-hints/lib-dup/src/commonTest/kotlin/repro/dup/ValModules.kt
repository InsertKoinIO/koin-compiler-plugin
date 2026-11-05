package repro.dup

import org.koin.dsl.koinApplication
import org.koin.dsl.module

// #144, row 2: two `val` modules in one test file, each providing an internal type of the main compilation.
internal val moduleA = module { single<A> { AImpl() } }
internal val moduleB = module { single<B> { BImpl() } }

internal fun valApp() = koinApplication { modules(moduleA, moduleB) }
