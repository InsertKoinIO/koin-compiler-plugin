// FILE: test.kt
// Regression test for KTZ-4050 / GH koin#2368.
//
// `@ScopeId(name = "a") val t: Tnt` on a factory constructor parameter should
// emit `scope.getScope("a").get<Tnt>()` at runtime, not `scope.get<Tnt>()`
// (which falls back to `_root_` and fails).
//
// Equivalent pattern with `@ScopeId(MyScope::class)` was already working — this
// test pins the string-name variant.
import org.koin.dsl.koinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Scope
import org.koin.core.annotation.Scoped
import org.koin.core.annotation.Factory
import org.koin.core.annotation.ScopeId
import org.koin.core.qualifier.named

interface MyType

@Scope(MyType::class)
@Scoped
class Tnt {
    val signature = "scoped-Tnt"
}

@Factory
class Lex(
    @ScopeId(name = "a") val t: Tnt,
)

@Module
@ComponentScan
class TestModule

fun box(): String {
    val koin = koinApplication { modules(TestModule().module()) }.koin
    koin.createScope("a", named<MyType>())

    val lex = koin.get<Lex>()
    return if (lex.t.signature == "scoped-Tnt") "OK"
    else "FAIL: lex.t.signature=${lex.t.signature}"
}
