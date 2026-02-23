// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Scoped
import org.koin.core.annotation.Scope

// Service in SessionScope requires AuthData, but AuthData is in UserScope (different scope)
@Module
@ComponentScan
class TestModule

class SessionScope
class UserScope

@Scoped
@Scope(SessionScope::class)
class Service(val auth: AuthData)

@Scoped
@Scope(UserScope::class)
class AuthData

/* GENERATED_FIR_TAGS: classDeclaration, classReference, primaryConstructor, propertyDeclaration */
