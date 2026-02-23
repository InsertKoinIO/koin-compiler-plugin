// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Singleton

// Lazy<Repository> still requires Repository to be provided, but it is NOT
@Module
@ComponentScan
class TestModule

@Singleton
class Service(val repo: Lazy<Repository>)

class Repository

/* GENERATED_FIR_TAGS: classDeclaration, primaryConstructor, propertyDeclaration */
