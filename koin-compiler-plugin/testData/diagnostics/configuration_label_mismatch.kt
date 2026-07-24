// RUN_PIPELINE_TILL: BACKEND
// A3 RESHAPE — scoped A2→A3 authority shift (intentional D001 → W002 at a leaf).
//
// Two @Configuration groups in a LEAF compilation (no startKoin / @KoinApplication here):
// CoreModule @Configuration("core") scans core.Repository; ServiceModule @Configuration("service")
// scans service.Service, which needs Repository. Whether Repository resolves depends entirely on
// the DOWNSTREAM app's @KoinApplication(configurations=[…]) label selection — unknowable at this
// leaf. So A2 now DEFERS (KOIN-W002) rather than hard-erroring: a leaf can't decide graph
// resolution. The entry point that assembles the real label set emits KOIN-D001 if Repository is
// genuinely absent there. (Before the shift this was a hard KOIN-D001 at the leaf — an over-strict
// closed-world assumption, the same false-positive class as the cross-module scanned graphs that
// DO resolve at an entry point. Reviewed behavior change.)
// FILE: core/Repository.kt
package core

import org.koin.core.annotation.Singleton

@Singleton
class Repository

// FILE: service/Service.kt
package service

import core.Repository
import org.koin.core.annotation.Singleton

// Service needs Repository, but ServiceModule has a different @Configuration label
@Singleton
class Service(val repo: Repository)

// FILE: modules.kt
import org.koin.core.annotation.Module
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration

// CoreModule is in "core" configuration group
@Module
@ComponentScan("core")
@Configuration("core")
class CoreModule

// ServiceModule is in "service" configuration group — different label, so Repository is NOT visible
@Module
@ComponentScan("service")
@Configuration("service")
class ServiceModule

/* GENERATED_FIR_TAGS: classDeclaration, primaryConstructor, propertyDeclaration */
