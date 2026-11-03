// RUN_PIPELINE_TILL: BACKEND
// Guard: YModule's scan is empty but it has a member provider (repo) that no relay can
// carry. The relay must not claim it complete and empty: Missing0 can't be checked at app, so the
// skip must stay disclosed.
//
// EXPECTED: KOIN-W008 (never silence).

// MODULE: lib0
// FILE: lib0/Lib0.kt
package lib0

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

class Missing0
class Repo(val dep: Missing0)

@Module
@ComponentScan("lib0.none")
class YModule {
    @Single
    fun repo(dep: Missing0): Repo = Repo(dep)
}

// MODULE: lib1(lib0)
// FILE: lib1/Lib1.kt
package lib1

import lib0.YModule
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module

@Module(includes = [YModule::class])
@ComponentScan("lib1.none")
class XModule

// MODULE: lib2(lib1)
// FILE: lib2/Lib2.kt
package lib2

import lib1.XModule
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

@Single
class Lib2Thing

@Module(includes = [XModule::class])
@ComponentScan("lib2")
class Lib2Module

// MODULE: app(lib2)
// FILE: app/App.kt
package app

import lib2.Lib2Module
import lib2.Lib2Thing
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.KoinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.plugin.module.dsl.startKoin

@Single
class Screen(val thing: Lib2Thing)

@Module(includes = [Lib2Module::class])
@ComponentScan("app")
class AppModule

@KoinApplication(modules = [AppModule::class])
object MainApplication

fun main() {
    startKoin<MainApplication> {}
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, collectionLiteral, functionDeclaration, lambdaLiteral,
objectDeclaration, primaryConstructor, propertyDeclaration, stringLiteral */
