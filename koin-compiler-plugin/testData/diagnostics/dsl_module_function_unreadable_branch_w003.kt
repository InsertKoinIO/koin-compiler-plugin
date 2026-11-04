// RUN_PIPELINE_TILL: BACKEND
// one branch can't be read (a member call on an object). Counting only the readable branch
// would be a false KOIN-D002 for RemoteOnly: the loaded set is unknown instead (KOIN-W003).

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

val localModule = module { single<LocalOnly>() }
val remoteModule = module { single<RemoteOnly>() }

object Remote {
    fun module(): Module = remoteModule
}

fun storage(local: Boolean): Module = if (local) localModule else Remote.module()

fun main() {
    val koin = koinApplication { modules(module { single<Config>() }, storage(false)) }.koin
    koin.get<RemoteOnly>()
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, ifExpression, lambdaLiteral, localProperty,
objectDeclaration, primaryConstructor, propertyDeclaration */
