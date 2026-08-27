// FILE: test.kt
// Real-world shape (Kotzilla server): two SEPARATE anonymous `single<Clock> { ... }` blocks (no
// val/fun id) in the same file, each inside its own inline `module { }` used by a different
// koinApplication { } call. Both provide the SAME type, both provider-only (no bindings, no
// requirements, no qualifier), so their generated `dsl_single` hint functions are IDENTICAL at the
// JVM level — same name, same erased parameter types (Unit-typed markers all erase to
// `Lkotlin/Unit;`; parameter NAMES don't affect the descriptor). Both land in the SAME batched-per-
// file hint class (generateDslDefinitionHints groups by module id, falling back to source file when
// there is none). Two methods with an identical (name, descriptor) pair in one class file is invalid
// bytecode — caught at class-LOAD time (`Duplicate method name "dsl_single" with signature
// "(Lpkg.Clock;Lkotlin.Unit;Lkotlin.Unit;)V"`), not at compile time, so it surfaced far downstream
// of its actual cause.
//
// EXPECTED: both koinApplication { } calls resolve their own Clock successfully — proves the
// disambiguation (disambiguateDuplicateSignatures) makes the two hint functions' descriptors diverge
// without breaking decoding of either.
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Clock

fun useFirst(): Clock {
    val koin = koinApplication {
        modules(module { single<Clock> { Clock() } })
    }.koin
    return koin.get()
}

fun useSecond(): Clock {
    val koin = koinApplication {
        modules(module { single<Clock> { Clock() } })
    }.koin
    return koin.get()
}

fun box(): String {
    useFirst()
    useSecond()
    return "OK"
}
