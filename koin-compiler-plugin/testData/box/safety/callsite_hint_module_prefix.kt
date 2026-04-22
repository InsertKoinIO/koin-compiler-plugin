// FILE: test.kt
// Regression for #20: call-site hint files must be prefixed by a module identifier
// so two Gradle modules that both koinInject<SameType>() don't produce identical
// class names (Android dex merge fails on duplicate `org.koin.plugin.hints.XxxCallsiteKt`).
//
// This library-style compilation has compile-safety on, a Koin.get<UnresolvedType>()
// call, and NO startKoin / @KoinApplication entry point. With that shape the plugin
// emits a call-site hint for downstream validation — exactly the path that used to
// produce filename collisions.
//
// The golden .fir.ir.txt must contain `fileName:.*_myService_callsite.kt` — the
// module-prefixed form — not the old `fileName:myService_callsite.kt`.
import org.koin.core.Koin

interface MyService

class LibraryConsumer {
    fun doWork(koin: Koin): MyService = koin.get()
}

fun box(): String = "OK"
