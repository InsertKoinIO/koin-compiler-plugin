// RUN_PIPELINE_TILL: BACKEND
// a @Configuration module whose definition uses an internal typed scope, relayed again by
// plain downstream modules (each relays @Configuration hints it sees). A re-relayed erased class must
// neither crash the compiler nor produce invalid IR.
//
// EXPECTED: no diagnostics.

// MODULE: data
// FILE: data/Data.kt
package data

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module
import org.koin.core.annotation.Scope
import org.koin.core.annotation.Scoped

internal class SessionScope

@Scope(SessionScope::class)
@Scoped
class SessionCache

@Module
@Configuration
@ComponentScan("data")
class DataModule

// MODULE: feature(data)
// FILE: feature/Feature.kt
package feature

class FeatureThing

// MODULE: mid(feature)
// FILE: mid/Mid.kt
package mid

class MidThing

/* GENERATED_FIR_TAGS: classDeclaration, classReference, stringLiteral */
