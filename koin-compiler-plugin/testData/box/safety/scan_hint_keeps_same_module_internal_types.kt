// FILE: test.kt
// this module's own internal classes are nameable here: its scan hint names them directly,
// with no erasure marker (guards against erasing what doesn't need it).

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.dsl.koinApplication

interface Api

@Single
internal class InternalImpl : Api

internal class Holder {
    @Single
    class NestedInInternal
}

@Module
@ComponentScan
class TestModule

fun box(): String {
    val koin = koinApplication { modules(TestModule().module()) }.koin
    return if (koin.get<Api>() is InternalImpl) "OK" else "FAIL"
}
