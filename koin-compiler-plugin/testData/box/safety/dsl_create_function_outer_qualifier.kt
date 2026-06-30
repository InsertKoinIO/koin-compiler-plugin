// FILE: test.kt
import org.koin.core.annotation.Named
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.create
import org.koin.plugin.module.dsl.single

class DemoRoot

class QualifiedConsumer(
    @Named("Demo1") val root: DemoRoot
)

fun createDemoRoot(): DemoRoot = DemoRoot()

val appModule = module {
    single(qualifier = named("Demo1")) { create(::createDemoRoot) }
    single<QualifiedConsumer>()
}

fun box(): String {
    val koin = koinApplication {
        modules(appModule)
    }.koin

    val consumer = koin.get<QualifiedConsumer>()
    return if (consumer.root is DemoRoot) "OK" else "FAIL"
}
