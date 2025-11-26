// FILE: test.kt
import org.koin.dsl.module
import org.koin.dsl.koinApplication
import org.koin.plugin.module.dsl.single

class HeavyDep {
    companion object {
        var created = false
    }
    init {
        created = true
    }
}

class Consumer(val lazyDep: Lazy<HeavyDep>)

fun box(): String {
    HeavyDep.created = false

    val m = module {
        single<HeavyDep>()
        single<Consumer>()
    }
    val koin = koinApplication { modules(m) }.koin

    val consumer = koin.get<Consumer>()

    // Lazy should not have created HeavyDep yet
    val beforeAccess = HeavyDep.created

    // Access the lazy value
    val dep = consumer.lazyDep.value

    // Now it should be created
    val afterAccess = HeavyDep.created

    return if (!beforeAccess && afterAccess && dep != null) {
        "OK"
    } else {
        "FAIL: Lazy<T> parameter not working (before=$beforeAccess, after=$afterAccess)"
    }
}
