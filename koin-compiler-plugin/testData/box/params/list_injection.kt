// FILE: test.kt
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Named
import org.koin.core.annotation.Singleton
import org.koin.dsl.koinApplication

@Module
@ComponentScan
class TestModule

interface Plugin {
    fun name(): String
}

@Singleton
@Named("plugin1")
class Plugin1 : Plugin {
    override fun name() = "Plugin1"
}

@Singleton
@Named("plugin2")
class Plugin2 : Plugin {
    override fun name() = "Plugin2"
}

@Singleton
class PluginManager(val plugins: List<Plugin>)

fun box(): String {
    val koin = koinApplication {
        modules(TestModule().module())
    }.koin

    val pluginNames = koin.get<PluginManager>().plugins.map { it.name() }.sorted()

    return if (pluginNames == listOf("Plugin1", "Plugin2")) {
        "OK"
    } else {
        "FAIL: expected [Plugin1, Plugin2], got $pluginNames"
    }
}
