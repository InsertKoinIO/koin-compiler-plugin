// FILE: test.kt
import org.koin.dsl.koinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Singleton
import org.koin.core.annotation.Qualifier
import org.koin.core.qualifier.named

// Custom qualifier annotation (plain, no value arg) — should be treated as TypeQualifier
@Qualifier
annotation class BaseUrl

@Qualifier
annotation class AuthUrl

@Module
@ComponentScan
class TestModule

@Singleton
@BaseUrl
fun provideBaseUrl(): String = "https://api.example.com"

@Singleton
@AuthUrl
fun provideAuthUrl(): String = "https://auth.example.com"

@Singleton
class UrlConsumer(
    @BaseUrl val base: String,
    @AuthUrl val auth: String,
)

fun box(): String {
    val koin = koinApplication {
        modules(TestModule().module())
    }.koin

    // Runtime injection via reified named<T>() (TypeQualifier)
    val base = koin.get<String>(named<BaseUrl>())
    val auth = koin.get<String>(named<AuthUrl>())

    val consumer = koin.get<UrlConsumer>()

    return when {
        base != "https://api.example.com" -> "FAIL: named<BaseUrl>() resolved to $base"
        auth != "https://auth.example.com" -> "FAIL: named<AuthUrl>() resolved to $auth"
        consumer.base != base -> "FAIL: consumer.base mismatch"
        consumer.auth != auth -> "FAIL: consumer.auth mismatch"
        else -> "OK"
    }
}
