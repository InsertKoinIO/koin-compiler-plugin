package examples.annotations.features

import org.koin.core.annotation.*

// ============================================================================
// Test: @Property annotation (getProperty injection)
// ============================================================================

@Factory
class ConfigurableClient(
    @Property("api.baseUrl") val baseUrl: String,
    @Property("api.timeout") val timeout: Int
)

// ============================================================================
// Test: Nullable @Property
// ============================================================================

@Factory
class OptionalConfigClient(
    @Property("optional.key") val optionalValue: String?
)

// ============================================================================
// Test: List<T> dependencies (getAll injection)
// ============================================================================

interface Plugin {
    fun name(): String
}

@Single
@Named("plugin1")
class Plugin1 : Plugin {
    override fun name() = "Plugin1"
}

@Single
@Named("plugin2")
class Plugin2 : Plugin {
    override fun name() = "Plugin2"
}

@Single
class PluginManager(val plugins: List<Plugin>)

// ============================================================================
// Test: @InjectedParam (parametersOf injection)
// ============================================================================

@Factory
class UserPresenter(@InjectedParam val userId: Int, val service: MySingleService)

// ============================================================================
// Test: Combined features
// ============================================================================

@Factory
class ComplexService(
    val service: MySingleService,                      // Regular get()
    @Named("production") val repo: Repository,        // get(named("production"))
    val optionalLogger: Logger?,                       // getOrNull()
    val plugins: List<Plugin>,                         // getAll()
    @Property("service.name") val serviceName: String, // getProperty()
    @InjectedParam val requestId: String              // parametersHolder.get()
)
