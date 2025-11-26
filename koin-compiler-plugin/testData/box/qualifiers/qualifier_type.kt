// FILE: test.kt
import org.koin.dsl.koinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Singleton
import org.koin.core.annotation.Qualifier
import org.koin.plugin.module.dsl.typeQualifier

@Module
@ComponentScan
class TestModule

// Marker interface for type qualifier
interface ProdQualifier

@Singleton
@Qualifier(ProdQualifier::class)
class QualifiedService

@Singleton
class Consumer(@Qualifier(ProdQualifier::class) val service: QualifiedService)

fun box(): String {
    val koin = koinApplication {
        modules(TestModule().module())
    }.koin

    val service = koin.get<QualifiedService>(typeQualifier(ProdQualifier::class))
    val consumer = koin.get<Consumer>()

    return if (consumer.service === service) "OK" else "FAIL: @Qualifier(Type::class) not working"
}
