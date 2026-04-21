// FILE: service/Services.kt
package service

import org.koin.core.annotation.Singleton
import org.koin.core.annotation.Named

interface Url { val value: String }

// Two top-level @Named @Singleton functions returning the SAME type (String-wrapping Url impl).
// Exercises the per-qualifier entry hint + roster mechanism — the consumer in a different
// @Configuration must discover both via the roster hint and resolve them by qualifier.
@Singleton
@Named("baseUrl")
fun provideBaseUrl(): Url = object : Url { override val value = "https://api.example.com" }

@Singleton
@Named("authUrl")
fun provideAuthUrl(): Url = object : Url { override val value = "https://auth.example.com" }

// FILE: consumer/UrlClient.kt
package consumer

import service.Url
import org.koin.core.annotation.Singleton
import org.koin.core.annotation.Named

@Singleton
class UrlClient(
    @Named("baseUrl") val base: Url,
    @Named("authUrl") val auth: Url,
)

// FILE: modules.kt
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration

@Module
@ComponentScan("service")
@Configuration
class ServiceModule

@Module
@ComponentScan("consumer")
@Configuration
class ConsumerModule

// FILE: test.kt
import org.koin.dsl.koinApplication

fun box(): String {
    val koin = koinApplication {
        modules(ServiceModule().module(), ConsumerModule().module())
    }.koin

    val client = koin.get<consumer.UrlClient>()

    val baseOk = client.base.value == "https://api.example.com"
    val authOk = client.auth.value == "https://auth.example.com"

    return if (baseOk && authOk) "OK" else "FAIL: base=${client.base.value} auth=${client.auth.value}"
}
