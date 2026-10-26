package repro.wide

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.dsl.module
import org.koin.mp.KoinPlatform.getKoin

// One shape per IR-built hint kind. Comment = hint it must carry across modules.

@Single // componentscan_ + injectedparams_
class Greeter(@InjectedParam val name: String)

class Engine
@Single // componentscanfunc_
fun engine(): Engine = Engine()

class Tire // never provided
class Car(val engine: Engine, val tire: Tire)

@Single // funcreqs_: a top-level function's requirements (Engine, Tire) must cross
fun car(engine: Engine, tire: Tire): Car = Car(engine, tire)

@Module
@ComponentScan("repro.wide")
class WideModule

@Module(includes = [WideModule::class]) // annotationincludes_
class WideRoot

class Wheel
val wheelModule = module { single<Wheel> { Wheel() } } // dsl_
val dslRoot = module { includes(wheelModule) }         // dslincludes_

class AppOnly // never provided
fun appOnly(): AppOnly = getKoin().get() // callsite: no entry point here, deferred to the app
