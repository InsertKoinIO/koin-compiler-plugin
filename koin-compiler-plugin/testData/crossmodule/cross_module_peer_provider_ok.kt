// RUN_PIPELINE_TILL: BACKEND
// Direct regression test for the measured false positive that motivated removing A2 in 1.1.0
// (see docs/COMPILE_SAFETY_A3_PLAN.md): a module validated in ISOLATION cannot know how it will
// be wired into a larger app. `notifications` needs PeerService, which is genuinely provided —
// but only by `peer`, a module `notifications` has no Gradle dependency on at all. The two are
// only unified downstream, at `app`'s entry point.
//
// Before A2 removal: `notifications`, validated on its own during annotation processing, had no
// classpath edge to `peer`'s provider and hard-errored KOIN-D001 for a dependency that IS
// satisfied once the real app assembles both modules together — exactly this shape, minus the
// Gradle dependency edge from notifications to peer, which never needs to exist. Now: only A3, at
// the entry point below, verifies — seeing the true assembled graph rather than a false negative
// derived from an isolated, incomplete view.
//
// EXPECTED: empty .errors.txt — the graph is genuinely complete once assembled at MyApp.

// MODULE: contracts
// FILE: contracts/PeerService.kt
package contracts

interface PeerService {
    fun ping(): String
}

// MODULE: notifications(contracts)
// FILE: notifications/NotificationService.kt
package notifications

import contracts.PeerService
import org.koin.core.annotation.Singleton

// Needs PeerService, but this module has NO Gradle dependency on whoever implements it — only
// on the `contracts` module declaring the interface.
@Singleton
class NotificationService(val peer: PeerService)

// FILE: notifications/NotificationsModule.kt
package notifications

import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan

@Module
@ComponentScan("notifications")
class NotificationsModule

// MODULE: peer(contracts)
// FILE: peer/PeerServiceImpl.kt
package peer

import contracts.PeerService
import org.koin.core.annotation.Singleton

@Singleton
class PeerServiceImpl : PeerService {
    override fun ping(): String = "pong"
}

// FILE: peer/PeerModule.kt
package peer

import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan

@Module
@ComponentScan("peer")
class PeerModule

// MODULE: app(notifications, peer)
// FILE: app/App.kt
package app

import org.koin.core.annotation.KoinApplication
import org.koin.plugin.module.dsl.startKoin
import notifications.NotificationsModule
import peer.PeerModule

@KoinApplication(modules = [NotificationsModule::class, PeerModule::class])
object MyApp

fun main() {
    startKoin<MyApp> {}
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, collectionLiteral, functionDeclaration, interfaceDeclaration,
lambdaLiteral, objectDeclaration, override, primaryConstructor, propertyDeclaration, stringLiteral */
