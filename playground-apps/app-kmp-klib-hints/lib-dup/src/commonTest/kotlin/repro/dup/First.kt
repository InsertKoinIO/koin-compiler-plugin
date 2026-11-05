package repro.dup

import org.koin.dsl.koinApplication
import org.koin.dsl.module

// #144, row 1: two internal types of the main compilation, one inline module per test file.
internal fun first() = koinApplication { modules(module { single<A> { AImpl() } }) }
