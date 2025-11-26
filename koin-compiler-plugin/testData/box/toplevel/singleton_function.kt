// FILE: test.kt
package testpkg

import org.koin.dsl.koinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Singleton

interface DataSource
class DatabaseDataSource : DataSource

@Singleton
fun provideDataSource(): DataSource = DatabaseDataSource()

@Module
@ComponentScan("testpkg")
class TestModule

fun box(): String {
    val koin = koinApplication {
        modules(TestModule().module())
    }.koin

    val ds1 = koin.get<DataSource>()
    val ds2 = koin.get<DataSource>()

    val isSingleton = ds1 === ds2
    val correctType = ds1 is DatabaseDataSource

    return if (isSingleton && correctType) "OK" else "FAIL: top-level @Singleton function not working"
}
