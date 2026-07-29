// RUN_PIPELINE_TILL: BACKEND
// 1.1.0 — A2 removed; this leaf compilation now emits NO diagnostic at all (empty golden).
//
// Two @Configuration groups in a LEAF compilation (no startKoin / @KoinApplication here):
// CoreModule @Configuration("core") scans core.Repository; ServiceModule @Configuration("service")
// scans service.Service, which needs Repository. Whether Repository resolves depends entirely on
// the DOWNSTREAM app's @KoinApplication(configurations=[…]) label selection — unknowable at this
// leaf. A2 used to defer this (KOIN-W002, now deleted); A3 is now the sole verifier and only runs
// at an entry point, which this compilation doesn't have — generation only, no verification (see
// CompileSafetyValidator's class doc). The remedy is an entry point, where a genuinely-absent
// Repository still emits KOIN-D001.
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
