package examples.annotations.features

import org.koin.core.annotation.*

// ============================================================================
// Test: @Qualifier annotation with name
// ============================================================================

interface Cache

@Single
@Qualifier(name = "inMemory")
class InMemoryCache : Cache

@Single
@Qualifier(name = "redis")
class RedisCache : Cache

// ============================================================================
// Test: Custom qualifier annotations
// ============================================================================

@Named
annotation class InMemory

@Named
annotation class Disk

@Single
@InMemory
class InMemoryStorage

@Single
@Disk
class DiskStorage

// Using custom qualifiers on parameters
@Factory
class StorageManager(
    @param:InMemory val memory: InMemoryStorage,
    @param:Disk val disk: DiskStorage
)

// ============================================================================
// Test: @Qualifier annotation with type (generates typeQualifier<T>())
// ============================================================================
// Note: Requires @Qualifier annotation to have a `value: KClass<*>` parameter
// When available, these examples will generate typed qualifiers:
//
// @Single
// @Qualifier(InMemoryCache::class)
// class TypedInMemoryCache : Cache
//
// @Factory
// class CacheConsumer(
//     @param:Qualifier(InMemoryCache::class) val cache: Cache
// )
//
// The above generates:
//   single<TypedInMemoryCache>(typeQualifier<InMemoryCache>()) { ... }
//   factory<CacheConsumer> { CacheConsumer(get(typeQualifier<InMemoryCache>())) }

// ============================================================================
// Test: JSR-330 @Inject annotation (generates factory)
// ============================================================================

// Note: jakarta.inject.Inject on a class generates a factory definition
// @Inject
// class InjectableService(val storage: InMemoryStorage)

// JSR-330 @Inject on constructor marks which constructor to use
// class MultiConstructorService {
//     val storage: InMemoryStorage
//
//     @jakarta.inject.Inject
//     constructor(storage: InMemoryStorage) {
//         this.storage = storage
//     }
//
//     constructor() {
//         this.storage = InMemoryStorage()
//     }
// }
