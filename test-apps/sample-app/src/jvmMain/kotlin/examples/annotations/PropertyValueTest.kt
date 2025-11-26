package examples.annotations

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Factory
import org.koin.core.annotation.KoinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.Property
import org.koin.core.annotation.PropertyValue

// Default value for the "app.name" property
@PropertyValue("app.name")
val defaultAppName = "MyDefaultApp"

// Default value for the "app.version" property
@PropertyValue("app.version")
val defaultAppVersion = "1.0.0"

// Class that uses @Property with @PropertyValue defaults
@Factory
class AppConfig(
    @Property("app.name") val appName: String,
    @Property("app.version") val version: String
)

@Module
@ComponentScan("examples.annotations")
class PropertyValueModule

@KoinApplication(modules = [PropertyValueModule::class])
object PropertyValueApp
