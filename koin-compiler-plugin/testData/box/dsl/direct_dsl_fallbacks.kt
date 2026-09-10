// FILE: test.kt
// KTZ-4829: shapes direct registration must NOT take, because part of the key is not literal at
// the call site. They stay on the runtime DSL and must still resolve exactly as before: a qualifier
// held in a variable, createdAtStart from a variable, a lambda passed through a variable of an
// unknown-scope block, singleOf(::T). The .fir.ir.txt golden is the assertion that these did not
// become saveMapping blocks.
package sample.dsl

import org.koin.dsl.module
import org.koin.dsl.koinApplication
import org.koin.plugin.module.dsl.create
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.Qualifier
import org.koin.core.qualifier.named

class Repository
class Other(val repository: Repository)
class Third(val repository: Repository)

fun qualifierFromElsewhere(): Qualifier = named("dynamic")

fun box(): String {
    val dynamic = qualifierFromElsewhere()
    val eagerFlag = System.getProperty("nope") != null
    val m = module {
        single { create(::Repository) }
        single(dynamic) { create(::Other) }                 // qualifier not literal -> runtime DSL
        single(createdAtStart = eagerFlag) { create(::Third) } // createdAtStart not literal -> runtime DSL
        singleOf(::Third)                                   // constructor shorthand -> untouched
    }
    val koin = koinApplication { modules(m) }.koin

    if (koin.get<Other>(dynamic).repository !== koin.get<Repository>()) return "FAIL: dynamic qualifier definition"
    if (koin.getOrNull<Other>() != null) return "FAIL: dynamic qualifier ignored"
    if (koin.get<Third>() == null) return "FAIL: singleOf / non-literal createdAtStart definition"
    return "OK"
}
