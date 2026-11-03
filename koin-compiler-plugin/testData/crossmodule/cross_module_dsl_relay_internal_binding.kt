// RUN_PIPELINE_TILL: BACKEND
// an `internal` implementation bound to a public interface, with an `internal` requirement,
// relayed through `mid`. `app` sees only `mid` and `contracts`, and needs the public interface, so the relay must keep the binding and
// the requirement while never naming the internal types in `mid`'s own IR.
//
// EXPECTED: no compiler crash; the graph validates (no diagnostics).

// MODULE: contracts
// FILE: contracts/Repo.kt
package contracts

interface Repo

// MODULE: feature(contracts)
// FILE: feature/Feature.kt
package feature

import contracts.Repo
import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

internal class Cache
internal class RepoImpl(val cache: Cache) : Repo

val featureModule = module {
    single<Cache>()
    single<RepoImpl>() bind Repo::class
}

// MODULE: mid(feature)
// FILE: mid/Mid.kt
package mid

import feature.featureModule
import org.koin.dsl.module

val midModule = module { includes(featureModule) }

// MODULE: app(mid, contracts)
// FILE: app/App.kt
package app

import contracts.Repo
import mid.midModule
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Screen(val repo: Repo)

fun main() {
    startKoin { modules(module { single<Screen>() }, midModule) }
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, interfaceDeclaration, lambdaLiteral, outProjection,
primaryConstructor, propertyDeclaration */
