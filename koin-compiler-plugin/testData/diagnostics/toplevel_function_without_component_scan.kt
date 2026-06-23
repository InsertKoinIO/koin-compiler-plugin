// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.core.context.startKoin
import org.koin.dsl.module

class JsonLike

@Single
fun json(): JsonLike = JsonLike()

@Module
class TestModule

fun main() {
    val app = startKoin {
        modules(TestModule().module())
    }

    app.koin.get<JsonLike>()
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral */
