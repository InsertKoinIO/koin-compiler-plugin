// FILE: test.kt
import org.koin.dsl.koinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Singleton
import org.koin.core.annotation.Property

@Module
@ComponentScan
class TestModule

@Singleton
class Config(
    @Property("server.host") val host: String,
    @Property("server.port") val port: String
)

fun box(): String {
    val koin = koinApplication {
        properties(mapOf(
            "server.host" to "localhost",
            "server.port" to "8080"
        ))
        modules(TestModule().module())
    }.koin

    val config = koin.get<Config>()

    return if (config.host == "localhost" && config.port == "8080") {
        "OK"
    } else {
        "FAIL: @Property not working (host=${config.host}, port=${config.port})"
    }
}
