// FILE: test.kt
// singleOf(::Ctor) { named<T>() } — reified type qualifier from the options block, the second
// named() form (string qualifier covered by dsl_singleof_named_string_qualifier_ok.kt).
import org.koin.core.module.dsl.named
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module

interface Env
object Prod : Env

class Config

val appModule = module {
    singleOf(::Config) { named<Prod>() }
}

fun box(): String {
    val koin = koinApplication { modules(appModule) }.koin
    val config = koin.get<Config>(named<Prod>())
    return if (config != null) "OK" else "FAIL"
}
