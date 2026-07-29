// FILE: test.kt
import org.koin.dsl.koinApplication
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

// Real-world shape from issue #77: a stateless `object` bound to a framework interface
// and collected through it, on top of a shared abstract base.

interface Loader<K : Any, V : Any> {
    val loaderName: String
}

abstract class BaseLoader<K : Any, V : Any> : Loader<K, V> {
    override val loaderName: String = this::class.qualifiedName!!
    abstract suspend fun batchLoad(keys: Set<K>): Map<K, V>
}

@Single([Loader::class])
object LengthLoader : BaseLoader<String, Int>() {
    operator fun invoke() = this // KSP workaround, ignored by the compiler plugin

    override suspend fun batchLoad(keys: Set<String>): Map<String, Int> = keys.associateWith { it.length }
}

@Module
@ComponentScan
class AppModule

fun box(): String {
    val koin = koinApplication {
        modules(AppModule().module())
    }.koin

    // The @Single object is bound to the Loader interface and collected via getAll.
    val loaders = koin.getAll<Loader<*, *>>()
    if (loaders.size != 1) return "FAIL: expected 1 loader, got ${loaders.size}"
    if (loaders.first() !== LengthLoader) return "FAIL: bound loader is not the object INSTANCE"

    // Resolvable by the interface and by the concrete object type, both the INSTANCE.
    if (koin.get<Loader<String, Int>>() !== LengthLoader) return "FAIL: get<Loader>() is not the INSTANCE"
    if (koin.get<LengthLoader>() !== LengthLoader) return "FAIL: get<LengthLoader>() is not the INSTANCE"
    if (LengthLoader.loaderName != "LengthLoader") return "FAIL: wrong loaderName ${LengthLoader.loaderName}"

    return "OK"
}
