// RUN_PIPELINE_TILL: BACKEND
// Cross-module qualifier resolution, post-A2-removal (1.1.0). A consumer needs a SPECIFIC
// @Named-qualified Repository; TWO peer modules each provide a DIFFERENTLY-qualified Repository,
// neither peer depending on the consumer or each other — only unified at app's entry point. This
// proves A3 matches qualifiers correctly across module boundaries (not just "some Repository
// exists somewhere"), which matters more now that A3 is the sole verifier for this shape.
//
// EXPECTED: empty .errors.txt — the "prod"-qualified requirement resolves against the "prod"
// provider in `prodRepo`, not the "test" provider in `testRepo`.

// MODULE: contracts
// FILE: contracts/Repository.kt
package contracts

interface Repository

// MODULE: prodRepo(contracts)
// FILE: prodRepo/ProdRepository.kt
package prodrepo

import contracts.Repository
import org.koin.core.annotation.Named
import org.koin.core.annotation.Singleton

@Singleton
@Named("prod")
class ProdRepository : Repository

// FILE: prodRepo/ProdModule.kt
package prodrepo

import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan

@Module
@ComponentScan("prodrepo")
class ProdModule

// MODULE: testRepo(contracts)
// FILE: testRepo/TestRepository.kt
package testrepo

import contracts.Repository
import org.koin.core.annotation.Named
import org.koin.core.annotation.Singleton

@Singleton
@Named("test")
class TestRepository : Repository

// FILE: testRepo/TestModule.kt
package testrepo

import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan

@Module
@ComponentScan("testrepo")
class TestModule

// MODULE: consumer(contracts)
// FILE: consumer/Service.kt
package consumer

import contracts.Repository
import org.koin.core.annotation.Named
import org.koin.core.annotation.Singleton

// Needs the "prod"-qualified Repository specifically — this module has no Gradle dependency on
// whoever provides EITHER qualified variant.
@Singleton
class Service(@Named("prod") val repo: Repository)

// FILE: consumer/ConsumerModule.kt
package consumer

import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan

@Module
@ComponentScan("consumer")
class ConsumerModule

// MODULE: app(consumer, prodRepo, testRepo)
// FILE: app/App.kt
package app

import org.koin.core.annotation.KoinApplication
import org.koin.plugin.module.dsl.startKoin
import consumer.ConsumerModule
import prodrepo.ProdModule
import testrepo.TestModule

@KoinApplication(modules = [ConsumerModule::class, ProdModule::class, TestModule::class])
object MyApp

fun main() {
    startKoin<MyApp> {}
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, collectionLiteral, functionDeclaration, interfaceDeclaration,
lambdaLiteral, objectDeclaration, primaryConstructor, propertyDeclaration, stringLiteral */
