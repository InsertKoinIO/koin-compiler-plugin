// RUN_PIPELINE_TILL: BACKEND
// GH #96 / #96: two top-level providers of Client with different @Named, the "bare" one has no
// parameters. It must not inherit the "sync" provider's requirements (SessionManager → AuthApi →
// @Named("bare") Client → false KOIN-D004 cycle, or a false KOIN-D001).
//
// EXPECTED: no diagnostics.

// MODULE: liba
// FILE: liba/A.kt
package liba

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Module
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

class Client

@Module
@ComponentScan("liba")
class ModuleA

@Factory
@Named("bare")
fun provideBareClient(): Client = Client()

@Single
class AuthApi(@Named("bare") val client: Client)

@Single
class SessionManager(val authApi: AuthApi)

// MODULE: libb(liba)
// FILE: libb/B.kt
package libb

import liba.Client
import liba.SessionManager
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Module
import org.koin.core.annotation.Named

@Module
@ComponentScan("libb")
class ModuleB

@Factory
@Named("sync")
fun provideSyncClient(sessionManager: SessionManager): Client = Client()

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
