package examples.annotations

import examples.annotations.other.A2
import examples.annotations.other.B2
import examples.annotations.scan.C2
import examples.annotations.scan.D2
import org.junit.After
import org.junit.Test
import org.koin.core.component.getScopeId
import org.koin.core.context.stopKoin
import org.koin.core.logger.Level
import org.koin.core.parameter.parametersOf
import org.koin.core.qualifier.named
import org.koin.plugin.module.dsl.startKoin

class AnnotationsTest{

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun run_annotation_test(){
        // Test @KoinApplication: startKoin<MyApp> auto-discovers @Module @ComponentScan classes
        // The compiler plugin transforms this to inject modules(listOf(MyModule().module, ...))
        val koin = startKoin<MyApp>{
            printLogger(Level.DEBUG)
        }.koin

        println("--- Testing @Singleton classes ---")
        println("A <- B <- C -> Lazy<A>")

        val a = koin.get<A>()
        println("a: $a")

        val b = koin.get<B>()
        println("b: $b")
        println("b.a: ${b.a}")
        assert(a == b.a) { "Singleton A should be same instance" }

        val c = koin.get<C>()
        println("c: $c")
        println("c.b: ${c.b}")
        println("c.a (lazy): ${c.a.value}")
        assert(b == c.b) { "Singleton B should be same instance" }
        assert(a == c.a.value) { "Lazy<A> should resolve to same singleton" }

        println("\n--- Testing nullable dependency ---")
        println("E -> D? (D is not registered)")
        val e = koin.get<E>()
        println("e: $e")
        println("e.d: ${e.d}")
        assert(e.d == null) { "D should be null (not registered)" }
        assert(koin.getOrNull<D>() == null) { "D should not be in container" }

        println("\n--- Testing @Factory classes ---")
        val f1 = koin.get<F>()
        val f2 = koin.get<F>()
        println("f1: $f1")
        println("f2: $f2")
        assert(f1 !== f2) { "Factory should create new instances" }
        assert(f1.a === a) { "Factory should inject singleton A" }

        val g1 = koin.get<G>()
        val g2 = koin.get<G>()
        println("g1: $g1")
        println("g2: $g2")
        assert(g1 !== g2) { "Factory should create new instances" }
        assert(g1.b === b) { "Factory should inject singleton B" }

        println("MyInterfaceImpl with auto-binding to MyInterface")
        val mi = koin.get<MyInterface>()  // Get via interface (auto-bound)
        println("mi:${mi}")
        val miImpl = koin.get<MyInterfaceImpl>()  // Get via impl directly
        println("miImpl:${miImpl}")
        assert(mi == miImpl) { "Singleton should return same instance via interface or impl" }

        println("\\n--- Testing function definitions in @Module ---")
        println("FunBuilder from myFunBuilder() function")
        val myFun = koin.get<FunBuilder>()
        println("myFun:${myFun}")
        assert(myFun.a == a) { "FunBuilder should inject singleton A" }
        assert(myFun.d == null) { "FunBuilder.d should be null (D not registered)" }

        println("FunLazyBuilder from myFunLazyBuilder() function")
        val myFunLazy = koin.get<FunLazyBuilder>()
        println("myFunLazy:${myFunLazy}")
        assert(myFunLazy.a == a) { "FunLazyBuilder should inject singleton A" }
        assert(myFunLazy.b.value == b) { "FunLazyBuilder should inject lazy singleton B" }

        assert(koin.get<FunLazyBuilder>() != koin.get<FunLazyBuilder>()) // is Factory

        println("\\n--- Testing @Named qualifier ---")
        println("MyInterfaceDumb with @Named qualifier on class")
        val dumb = koin.get<MyInterfaceDumb>(named("dumb"))
        println("dumb:${dumb}")
        assert(dumb.a == a) { "MyInterfaceDumb should inject singleton A" }

        println("DumbConsumer with @Named qualifier on parameter")
        val consumer = koin.get<DumbConsumer>()
        println("consumer:${consumer}")
        println("consumer.i:${consumer.i}")
        assert(consumer.i == dumb) { "DumbConsumer should inject the @Named(dumb) instance" }

        println("\\n--- Testing @InjectedParam ---")
        val param = 42
        val mcwp1 = koin.get<MyClassWithParam> { parametersOf(param) }
        val mcwp2 = koin.get<MyClassWithParam> { parametersOf(param) }
        println("mcwp1:${mcwp1}, mcwp2:${mcwp2}")
        assert(mcwp1 != mcwp2) { "Factory should create different instances" }
        assert(mcwp1.i == mcwp2.i) { "Both should have same injected param value" }
        assert(param == mcwp2.i) { "@InjectedParam should get value from parametersOf" }

        println("\\n--- Testing @KoinViewModel ---")
        println("MyViewModel with viewModel definition")
        val vm1 = koin.get<MyViewModel>()
        val vm2 = koin.get<MyViewModel>()
        println("vm1:${vm1}")
        println("vm2:${vm2}")
        assert(vm1 != vm2) { "Factory behavior - new instance each time" }
        assert(vm1.a == a) { "ViewModel should inject singleton A" }
        assert(vm1.b == b) { "ViewModel should inject singleton B" }
        println("ViewModel test passed!")

        val a2 = koin.get<A2>()
        println("a2: $a2")

        val b2 = koin.get<B2>()
        println("b2: $b2")
        println("b2.a: ${b2.a}")
        assert(a2 == b2.a) { "Singleton A should be same instance" }

        val c2 = koin.get<C2>()
        println("c2: $c2")

        val d2 = koin.get<D2>()
        println("d2: $d2")
        println("d2.c: ${d2.c}")
        assert(c2 == d2.c) { "Singleton A should be same instance" }

        val ms = MyScope()
        val msScope = koin.createScope<MyScope>(ms.getScopeId())
        val scopedStuff = msScope.get<MyScopedStuff>()
        println("scopedStuff: $scopedStuff")

        assert(koin.getOrNull<MyScopedStuff>() == null)

        println("\n✅ All annotation-based tests passed!")
    }
}

