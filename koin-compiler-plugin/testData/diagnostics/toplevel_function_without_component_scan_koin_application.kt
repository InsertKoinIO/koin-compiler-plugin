// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.dsl.koinApplication

class JsonLike

@Single
fun json(): JsonLike = JsonLike()

@Module
class TestModule

fun main() {
    val koin = koinApplication {
        modules(TestModule().module())
    }.koin

    koin.get<JsonLike>()
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral */
