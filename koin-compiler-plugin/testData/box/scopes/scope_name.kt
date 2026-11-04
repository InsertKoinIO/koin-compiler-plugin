// FILE: test.kt
// Regression test for koin-compiler-plugin#34.
//
// `@Scope(name = "session") @Scoped` (string-named scope) silently produced no bean
// definition before the fix — partition logic at `KoinAnnotationProcessor.buildModuleBody`
// only recognized typed `@Scope(MyScope::class)`, so the definition fell through to
// `rootDefinitions` where `buildScoped` on a `Module` receiver doesn't exist and the call
// was dropped. Both `@Scope(name=) + @Scoped` and `@Scope(name=) + @Scoped(binds=[...])`
// were affected.
import org.koin.dsl.koinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Scoped
import org.koin.core.annotation.Scope
import org.koin.core.qualifier.named

@Module
@ComponentScan
class TestModule

interface UserRepository {
    fun id(): String
}

// Case 1: @Scope(name=) + @Scoped + binds
@Scope(name = "session")
@Scoped(binds = [UserRepository::class])
class UserRepositoryImpl : UserRepository {
    override fun id() = "user-impl"
}

// Case 2: @Scope(name=) + @Scoped (no binds)
@Scope(name = "session")
@Scoped
class SessionData(val token: String = "session-token")

fun box(): String {
    val koin = koinApplication { modules(TestModule().module()) }.koin

    val session1 = koin.createScope("s1", named("session"))
    val session2 = koin.createScope("s2", named("session"))

    val repoA = session1.get<UserRepository>()
    val repoB = session1.get<UserRepository>()
    val repoC = session2.get<UserRepository>()

    val dataA = session1.get<SessionData>()
    val dataB = session1.get<SessionData>()

    session1.close()
    session2.close()

    val sameWithinScope = (repoA === repoB) && (dataA === dataB)
    val differentAcrossScopes = repoA !== repoC
    val boundToInterface = repoA is UserRepositoryImpl && repoA.id() == "user-impl"

    return if (sameWithinScope && differentAcrossScopes && boundToInterface) "OK"
    else "FAIL: sameWithinScope=$sameWithinScope differentAcrossScopes=$differentAcrossScopes boundToInterface=$boundToInterface"
}
