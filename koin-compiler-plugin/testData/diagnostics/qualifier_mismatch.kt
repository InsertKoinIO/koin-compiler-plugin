// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Singleton
import org.koin.core.annotation.Named

// Service requires @Named("prod") Repository, but only @Named("test") is provided
@Module
@ComponentScan
class TestModule

@Singleton
@Named("test")
class Repository

@Singleton
class Service(@Named("prod") val repo: Repository)

/* GENERATED_FIR_TAGS: classDeclaration, primaryConstructor, propertyDeclaration, stringLiteral */
