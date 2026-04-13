// FILE: data/Services.kt
package data

import org.koin.core.annotation.Singleton
import org.koin.core.annotation.Named

interface DataSource

// Qualifier name contains dots — the hint parameter name encoding must sanitize these
// since dots are not valid in Kotlin identifiers. Verifies the sanitize/unsanitize
// round-trip in cross-module hint metadata propagation.
@Singleton
@Named("com.example.local")
class LocalDataSource : DataSource

@Singleton
@Named("com.example.remote")
class RemoteDataSource : DataSource

// FILE: ui/Repository.kt
package ui

import data.DataSource
import org.koin.core.annotation.Singleton
import org.koin.core.annotation.Named

@Singleton
class Repository(
    @Named("com.example.local") val local: DataSource,
    @Named("com.example.remote") val remote: DataSource
)

// FILE: modules.kt
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration

@Module
@ComponentScan("data")
@Configuration
class DataModule

@Module
@ComponentScan("ui")
@Configuration
class UiModule

// FILE: test.kt
import org.koin.dsl.koinApplication

fun box(): String {
    val koin = koinApplication {
        modules(DataModule().module(), UiModule().module())
    }.koin

    val repo = koin.get<ui.Repository>()

    val localOk = repo.local is data.LocalDataSource
    val remoteOk = repo.remote is data.RemoteDataSource

    return if (localOk && remoteOk) "OK" else "FAIL: local=$localOk, remote=$remoteOk"
}
