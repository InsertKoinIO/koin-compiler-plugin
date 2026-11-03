// RUN_PIPELINE_TILL: BACKEND
// @Configuration auto-discovery reaching an empty-scan module two hops away must validate.
//
// EXPECTED: KOIN-D001 for app.Missing (not KOIN-W008).

// MODULE: lib
// FILE: lib/Lib.kt
package lib

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module

@Module
@Configuration
@ComponentScan("lib.none")
class LibModule

// MODULE: mid(lib)
// FILE: mid/Mid.kt
package mid

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

@Single
class MidThing

@Module
@Configuration
@ComponentScan("mid")
class MidModule

// MODULE: app(mid)
// FILE: app/App.kt
package app

import mid.MidThing
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.KoinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.plugin.module.dsl.startKoin

class Missing

@Single
class Screen(val thing: MidThing, val missing: Missing)

@Module
@Configuration
@ComponentScan("app")
class AppModule

@KoinApplication
object MainApplication

fun main() {
    startKoin<MainApplication> {}
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, lambdaLiteral, objectDeclaration, primaryConstructor,
propertyDeclaration, stringLiteral */
