// RUN_PIPELINE_TILL: BACKEND
// same @Configuration relay chain, with an internal type qualifier.
//
// EXPECTED: no diagnostics.

// MODULE: data
// FILE: data/Data.kt
package data

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module
import org.koin.core.annotation.Qualifier
import org.koin.core.annotation.Single

internal interface Prod

@Single
@Qualifier(Prod::class)
class Config

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

/* GENERATED_FIR_TAGS: classDeclaration, classReference, interfaceDeclaration, stringLiteral */
