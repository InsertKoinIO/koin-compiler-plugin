package examples.annotations.features

import androidx.lifecycle.ViewModel
import org.koin.core.annotation.*

// ============================================================================
// Test: Different definition types inside scope blocks
// This tests that @Factory, @Scoped, @KoinViewModel, @KoinWorker correctly
// generate buildFactory, buildScoped, buildViewModel, buildWorker on ScopeDSL
// ============================================================================

// Custom scope class for testing
class UserSession

// ============================================================================
// Test: @Scoped inside scope block -> buildScoped on ScopeDSL
// ============================================================================

@Scope(UserSession::class)
@Scoped
class UserSessionData

@Scope(UserSession::class)
@Scoped
class UserSessionCache(val data: UserSessionData)

// ============================================================================
// Test: @Factory inside scope block -> buildFactory on ScopeDSL
// ============================================================================

@Scope(UserSession::class)
@Factory
class UserSessionPresenter(val cache: UserSessionCache)

@Scope(UserSession::class)
@Factory
@Named("debug")
class DebugUserSessionPresenter(val cache: UserSessionCache)

// ============================================================================
// Test: @KoinViewModel inside scope block -> buildViewModel on ScopeDSL
// ============================================================================

@Scope(UserSession::class)
@KoinViewModel
class UserSessionViewModel(val data: UserSessionData) : ViewModel()

@Scope(UserSession::class)
@KoinViewModel
@Named("detailed")
class DetailedUserSessionViewModel(val data: UserSessionData, val cache: UserSessionCache) : ViewModel()

// ============================================================================
// Test: Multiple scope types with different definitions
// ============================================================================

class AdminSession

@Scope(AdminSession::class)
@Scoped
class AdminData

@Scope(AdminSession::class)
@Factory
class AdminPresenter(val data: AdminData)

@Scope(AdminSession::class)
@KoinViewModel
class AdminViewModel(val data: AdminData) : ViewModel()

// ============================================================================
// Test: @Named qualifier variations inside scopes
// ============================================================================

class FeatureScope

@Scope(FeatureScope::class)
@Scoped
@Named("primary")
class PrimaryFeatureData

@Scope(FeatureScope::class)
@Scoped
@Named("secondary")
class SecondaryFeatureData

@Scope(FeatureScope::class)
@Factory
class FeatureConsumer(
    @Named("primary") val primary: PrimaryFeatureData,
    @Named("secondary") val secondary: SecondaryFeatureData
)

// ============================================================================
// Test: Function definitions inside scopes
// ============================================================================

// Service classes for function-based definitions
class ScopedApiClient
class ScopedLogger
class ScopedAnalytics

// ============================================================================
// Module with function-based scoped definitions
// ============================================================================

@Module
@Configuration("scope-test")
class ScopeFunctionsModule {

    // @Scoped function inside scope -> buildScoped on ScopeDSL
    @Scope(UserSession::class)
    @Scoped
    fun scopedApiClient(): ScopedApiClient = ScopedApiClient()

    // @Factory function inside scope -> buildFactory on ScopeDSL
    @Scope(UserSession::class)
    @Factory
    fun scopedLogger(): ScopedLogger = ScopedLogger()

    // @Factory with @Named inside scope
    @Scope(UserSession::class)
    @Factory
    @Named("analytics")
    fun scopedAnalytics(): ScopedAnalytics = ScopedAnalytics()

    // @Scoped function in different scope
    @Scope(AdminSession::class)
    @Scoped
    fun adminApiClient(): ScopedApiClient = ScopedApiClient()
}

// ============================================================================
// Module that includes all scoped definitions via @ComponentScan
// ============================================================================

@Module
@ComponentScan("examples.annotations.features")
@Configuration("scope-test")
class ScopeDefinitionsModule

// App for testing scope definitions
@KoinApplication(configurations = ["scope-test"])
object ScopeDefinitionsApp
