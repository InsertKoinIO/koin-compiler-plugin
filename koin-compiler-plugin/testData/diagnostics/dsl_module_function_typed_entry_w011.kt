// RUN_PIPELINE_TILL: BACKEND
// the choice is disclosed for a typed entry point too, and the function's own includes()
// is not listed as a choice. A recursive call adds no choice.

import org.koin.core.annotation.KoinApplication
import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single
import org.koin.plugin.module.dsl.startKoin

class LocalOnly
class RemoteOnly
class DebugTools
class Other
class Common
class X(val c: Common)
class Clock
class Config

val otherModule = module { single<Other>() }
val commonModule = module { single<Common>() }
val clockModule = module { single<Clock>() }

fun feature(c: Boolean): Module = if (c) otherModule else module {
    includes(commonModule)
    single<X>()
}

fun nested(depth: Int): Module = if (depth <= 0) clockModule else nested(depth - 1)

@KoinApplication
object MyApp

fun main() {
    startKoin<MyApp> { modules(feature(false), nested(2)) }
}

/* GENERATED_FIR_TAGS: additiveExpression, classDeclaration, comparisonExpression, functionDeclaration, ifExpression,
integerLiteral, lambdaLiteral, objectDeclaration, primaryConstructor, propertyDeclaration */
