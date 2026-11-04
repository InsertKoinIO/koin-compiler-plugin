package repro.appwide

import org.koin.core.annotation.KoinApplication
import org.koin.mp.KoinPlatform.getKoin
import org.koin.plugin.module.dsl.startKoin
import repro.wide.Engine
import repro.wide.Greeter
import repro.wide.Wheel
import repro.wide.WideRoot
import repro.wide.dslRoot

@KoinApplication(modules = [WideRoot::class])
class WideApp

fun initKoin() {
    startKoin<WideApp> { modules(dslRoot) }
}

// Expected on EVERY target, same as jvm:
//   D006 Greeter (needs parametersOf)  <- injectedparams_
//   D001 Tire                          <- funcreqs_
//   D003 AppOnly                       <- callsite
// and nothing for Engine / Wheel       <- componentscanfunc_, annotationincludes_, dsl_, dslincludes_
fun greeter(): Greeter = getKoin().get()
fun engine(): Engine = getKoin().get()
fun wheel(): Wheel = getKoin().get()
