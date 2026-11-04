// RUN_PIPELINE_TILL: BACKEND
// #96 mirror of #96: an UNQUALIFIED zero-parameter provider of Client in one module, a @Named
// provider of Client that needs Consumer in another. The unqualified one must not pick up the named
// one's requirement through a shared bare carrier (Client → Consumer → Client: a false KOIN-D004).
//
// EXPECTED: no diagnostics.

// MODULE: liba
// FILE: liba/A.kt
package liba

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

class Client
class Consumer(val client: Client)

@Module
@ComponentScan("liba")
class ModuleA

@Single
fun provideClient(): Client = Client()

@Single
fun provideConsumer(client: Client): Consumer = Consumer(client)

// MODULE: libb(liba)
// FILE: libb/B.kt
package libb

import liba.Client
import liba.Consumer
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

@Module
@ComponentScan("libb")
class ModuleB

@Single
@Named("special")
fun provideSpecialClient(consumer: Consumer): Client = Client()

// MODULE: app(liba, libb)
// FILE: app/App.kt
package app

import liba.ModuleA
import libb.ModuleB
import org.koin.core.context.startKoin
import org.koin.plugin.module.dsl.modules

fun main() {
    startKoin { modules(ModuleA::class, ModuleB::class) }
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, functionDeclaration, lambdaLiteral, primaryConstructor,
propertyDeclaration, stringLiteral */
