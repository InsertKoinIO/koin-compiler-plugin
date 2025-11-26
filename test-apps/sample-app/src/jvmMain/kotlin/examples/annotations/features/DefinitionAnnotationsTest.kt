package examples.annotations.features

import org.koin.core.annotation.*

// ============================================================================
// Test: @Single/@Singleton on Classes
// ============================================================================

interface Repository

@Singleton
class MySingletonService

@Single
class MySingleService

@Single
@Named("production")
class ProductionRepository : Repository

// ============================================================================
// Test: @Factory on Classes
// ============================================================================

@Factory
class MyPresenter(val service: MySingleService)

@Factory
@Named("debug")
class DebugPresenter(val service: MySingleService)

// ============================================================================
// Test: binds parameter
// ============================================================================

interface Closeable {
    fun close()
}

@Single(binds = [Repository::class, Closeable::class])
class MyRepositoryImpl : Repository, Closeable {
    override fun close() {}
}

// ============================================================================
// Test: createdAtStart parameter
// ============================================================================

@Single(createdAtStart = true)
class EagerSingleton

// ============================================================================
// Test: @Scoped with @Scope
// ============================================================================

class MySessionScope

@Scope(MySessionScope::class)
@Scoped
class SessionData

@Scope(MySessionScope::class)
@Scoped
class SessionRepository(val data: SessionData)

// ============================================================================
// Test: @Scope with named scope
// ============================================================================

@Scope(name = "user_session")
@Scoped
class UserSessionService

// ============================================================================
// Test: @Named qualifier variations
// ============================================================================

@Single
@Named("local")
class LocalDataSource : Repository

@Single
@Named("remote")
class RemoteDataSource : Repository

// Class using @Named on constructor parameter
@Factory
class DataRepository(
    @Named("local") val localSource: Repository,
    @Named("remote") val remoteSource: Repository
)
