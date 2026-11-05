package repro.duppublic

import org.koin.dsl.koinApplication
import org.koin.dsl.module

// #144, row 5: the same public type provided by two `val` modules in one compilation. Crashed the KLIB
// serializer on 1.2.1 too, so it lives in its own module: a main-compilation crash would otherwise
// stop `:lib-dup`'s test compilations, where the 1.2.2 regression (rows 1-2) is, from running at all.
interface Shared
class SharedImpl : Shared

val sharedModule1 = module { single<Shared> { SharedImpl() } }
val sharedModule2 = module { single<Shared> { SharedImpl() } }

fun sharedApp1() = koinApplication { modules(sharedModule1) }
fun sharedApp2() = koinApplication { modules(sharedModule2) }
