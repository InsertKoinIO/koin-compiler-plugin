// FILE: test.kt
// KTZ-4829: direct registration computes the Koin index key at compile time. The box test runner
// enables verifyGeneratedKeys, so every registration below re-checks its key against indexKey()
// at module load — a wrong derivation throws before any assertion runs. Cases chosen to be the
// ones where the platform class name is not the Kotlin FQN:
//  - nested classes (JVM binary name uses '$')
//  - a package (root-package fixtures never exercise the "pkg." prefix)
//  - a type qualifier whose class is nested
//  - a typed scope whose scope class is nested
//  - a kotlin.* bound type, which must take the legacy fallback (kotlin.CharSequence::class.java.name
//    is java.lang.CharSequence — not derivable from the ClassId)
//  - createdAtStart together with a binding
//  - a function definition returning a nested type
package sample.direct

import org.koin.dsl.koinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Singleton
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Scoped
import org.koin.core.annotation.Scope
import org.koin.core.annotation.Named
import org.koin.core.annotation.Qualifier
import org.koin.core.qualifier.named
import org.koin.core.qualifier.qualifier

@Module
@ComponentScan
class TestModule {
    @Singleton
    fun provideNested(): Holder.Provided = Holder.Provided("fn")
}

class Holder {
    interface Api
    class Provided(val origin: String)

    @Singleton
    class Impl : Api

    @Singleton
    @Named("named-nested")
    class NamedImpl : Api

    class Marker

    @Singleton
    @Qualifier(Marker::class)
    class TypeQualified : Api

    class SessionScope

    @Scoped
    @Scope(SessionScope::class)
    class InSession : Api
}

object Counter { var eager = 0 }

@Singleton(createdAtStart = true, binds = [Holder.Api::class])
class EagerWithBinding : Holder.Api {
    init { Counter.eager++ }
}

@Factory(binds = [CharSequence::class])
class KotlinBound : CharSequence {
    override val length: Int get() = 0
    override fun get(index: Int): Char = ' '
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = ""
}

fun box(): String {
    val koin = koinApplication {
        modules(TestModule().module())
    }.koin

    // nested primary type, package prefix
    val impl = koin.get<Holder.Impl>()
    if (impl !is Holder.Api) return "FAIL: Impl not resolved by primary type"

    // string qualifier on a nested class
    val named = koin.get<Holder.NamedImpl>(named("named-nested"))
    if (named !is Holder.Api) return "FAIL: named nested class not resolved"

    // type qualifier whose class is nested
    val typeQualified = koin.get<Holder.TypeQualified>(qualifier<Holder.Marker>())
    if (typeQualified !is Holder.Api) return "FAIL: type-qualified nested class not resolved"

    // eager single with a binding: created at start, resolvable by the bound interface
    koin.createEagerInstances()
    if (Counter.eager != 1) return "FAIL: createdAtStart not honored (eager=${Counter.eager})"
    val byBinding = koin.getAll<Holder.Api>()
    if (byBinding.none { it is EagerWithBinding }) return "FAIL: binding of eager single not registered"

    // kotlin.* binding falls back to the DSL path and still resolves by the bound type
    if (koin.get<CharSequence>() !is KotlinBound) return "FAIL: kotlin.CharSequence binding not resolved"

    // function definition returning a nested type
    if (koin.get<Holder.Provided>().origin != "fn") return "FAIL: function definition not resolved"

    // typed scope with a nested scope class
    val scope = koin.createScope("s1", qualifier<Holder.SessionScope>())
    val inSession = scope.get<Holder.InSession>()
    if (scope.get<Holder.InSession>() !== inSession) return "FAIL: scoped definition not scoped"
    if (koin.getOrNull<Holder.InSession>() != null) return "FAIL: scoped definition leaked to root"

    return "OK"
}
