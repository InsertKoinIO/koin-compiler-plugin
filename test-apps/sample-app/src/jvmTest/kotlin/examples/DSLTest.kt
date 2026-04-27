package examples

import org.junit.Test
import org.koin.core.logger.Level
import org.koin.core.module.dsl.createdAtStart
import org.koin.plugin.module.dsl.create
import org.koin.plugin.module.dsl.factory
import org.koin.plugin.module.dsl.scoped
import org.koin.plugin.module.dsl.single
import org.koin.plugin.module.dsl.viewModel
import org.koin.core.module.dsl.withOptions
import org.koin.core.parameter.parametersOf
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.plugin.module.dsl.typeQualifier
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class DSLTest {

    @Test
    fun run_dsl_test() {
        println("Testing compiler plugin...")

        val m = module {
            // create
            single<A>()
            single<B>() withOptions { createdAtStart() }
            single<C>()
            factory<E>()
            factory<F>()

            // Test single<T>() - no constructor reference, just type parameter
            single<D>()
            scope(named("myScope")) {
                scoped<G>()
                factory<G2>()
            }
            single<H>()
            factory<I>()

            // interface binding, don't expose impl
            //single<MyInterface> { create(::MyInterfaceImpl) }
            // also
            single<MyInterfaceImpl>() bind MyInterface::class

            // secured function call
            single { create(::myFunBuilder) }
            single { create(::myFunLazyBuilder) }

            // Qualifiers - @Named
            single<MyInterfaceDumb>()
            single<DumbConsumer>()

            // Qualifiers - @Qualifier(name = "...")
            single<QualifiedService>()
            single<QualifiedConsumer>()

            // Qualifiers - @Qualifier(Type::class)
            single<TypeQualifiedService>()
            single<TypeQualifiedConsumer>()

            // Injected Params
            factory<MyClassWithParam>()

            // ViewModel
            viewModel<MyViewModel>()
            viewModel<MyViewModel2>()
        }
        val koin = koinApplication {
            printLogger(Level.DEBUG)
            modules(m)
        }.koin

        println("examples.A <- examples.B <- examples.C -> Lazy<examples.A>")
        val a = koin.get<A>()
        println("a:${a}")
        val b = koin.get<B>()
        println("b:${b}")
        println("b.a:${b.a}")
        assert(a == b.a)
        val c = koin.get<C>()
        println("c:${c}")
        println("c.b:${c.b}")
        assert(b == c.b)
        assert(a == c.a.value)

        println("examples.D with single<D>() - type parameter only")
        val d = koin.get<D>()
        println("d:${d}")

        println("examples.E -> examples.D (now D is registered via single<D>())")
        val e = koin.get<E>()
        println("e:${e}")
        println("e.d:${e.d}")
        assert(e.d == d)  // D is now registered, so e.d should be the singleton D

        println("examples.F with factory")
        val f = koin.get<F>()
        println("f:${f}")
        println("f.a:${f.a}")
        assert(a == f.a)

        println("examples.G with scoped")
        val scope = koin.createScope("test", named("myScope"))
        val g = scope.get<G>()
        println("g:${g}")
        println("g.b:${g.b}")
        assert(b == g.b)
        val g2 = scope.get<G2>()
        println("g:${g}")
        println("g.b:${g.b}")
        assert(b == g2.b)
        assert(g != g2)
        assert(scope.get<G2>() != g2)
        scope.close()

        val mi = koin.get<MyInterface>()
        println("mi:${mi}")

        println("examples.H with auto-injected dependencies")
        val h = koin.get<H>()
        println("h:${h}")
        println("h.a:${h.a}")
        println("h.b:${h.b}")
        assert(a == h.a)
        assert(b == h.b)

        println("examples.I with factory(::examples.I) - auto-injected with nullable")
        val i = koin.get<I>()
        println("i:${i}")
        println("i.a:${i.a}")
        println("i.d:${i.d}")
        assert(a == i.a)
        assert(i.d == d)  // D is registered via single<D>(), so i.d should be the singleton D

        val myFun = koin.get<FunBuilder>()
        println("myFun:${myFun}")
        assert(a == myFun.a)
        assert(myFun.d == d)  // D is now registered

        val myFunLazy = koin.get<FunLazyBuilder>()
        println("myFunLazy:${myFunLazy}")
        assert(a == myFunLazy.a)
        assert(b == myFunLazy.b.value)

        println("examples.MyInterfaceDumb with @Named qualifier on class")
        val dumb = koin.get<MyInterfaceDumb>(named("dumb"))
        println("dumb:${dumb}")
        assert(dumb.a == a)

        println("examples.DumbConsumer with @Named qualifier on parameter")
        val consumer = koin.get<DumbConsumer>()
        println("consumer:${consumer}")
        println("consumer.i:${consumer.i}")
        assert(consumer.i == dumb)  // Should get the same dumb instance via named qualifier

        println("examples.QualifiedService with @Qualifier(name=...) on class")
        val qualifiedService = koin.get<QualifiedService>(named("qualified"))
        println("qualifiedService:${qualifiedService}")
        assert(qualifiedService.a == a)

        println("examples.QualifiedConsumer with @Qualifier(name=...) on parameter")
        val qualifiedConsumer = koin.get<QualifiedConsumer>()
        println("qualifiedConsumer:${qualifiedConsumer}")
        println("qualifiedConsumer.service:${qualifiedConsumer.service}")
        assert(qualifiedConsumer.service == qualifiedService)  // Should get the same instance via qualifier

        println("examples.TypeQualifiedService with @Qualifier(Type::class) on class")
        val typeQualifiedService = koin.get<TypeQualifiedService>(typeQualifier(QualifierType::class))
        println("typeQualifiedService:${typeQualifiedService}")
        assert(typeQualifiedService.a == a)

        println("examples.TypeQualifiedConsumer with @Qualifier(Type::class) on parameter")
        val typeQualifiedConsumer = koin.get<TypeQualifiedConsumer>()
        println("typeQualifiedConsumer:${typeQualifiedConsumer}")
        println("typeQualifiedConsumer.service:${typeQualifiedConsumer.service}")
        assert(typeQualifiedConsumer.service == typeQualifiedService)  // Should get the same instance via type qualifier

        val param = 42
        val mcwp1 = koin.get<MyClassWithParam> { parametersOf(param) }
        val mcwp2 = koin.get<MyClassWithParam> { parametersOf(param) }
        assert(mcwp1 != mcwp2)
        assert(mcwp1.i == mcwp2.i)
        assert(param == mcwp2.i)

        println("examples.MyViewModel with viewModel(::examples.MyViewModel)")
        val vm1 = koin.get<MyViewModel>()
        val vm2 = koin.get<MyViewModel>()
        println("vm1:${vm1}")
        println("vm2:${vm2}")
        assert(vm1 != vm2)  // Factory behavior - new instance each time
        assert(vm1.a == a)
        assert(vm1.b == b)

        println("examples.MyViewModel2 with viewModel<MyViewModel>()")
        val vm22 = koin.get<MyViewModel2>()
        println("vm22:${vm22}")
        assert(vm22.a == a)
        assert(vm22.b == b)

        println("All tests passed! ✅")
    }

    @Test
    fun test_default_values() {
        println("Testing default values...")

        val m = module {
            single<A>()
            // ServiceWithDefaultValue has no A dependency, just a String with default value
            // String is NOT registered, so it should use the default value "default_name"
            single<ServiceWithDefaultValue>()
            // ServiceWithMixedParams has A (injected) + String and Int with defaults
            single<ServiceWithMixedParams>()
        }

        val koin = koinApplication {
            printLogger(Level.DEBUG)
            modules(m)
        }.koin

        println("ServiceWithDefaultValue - should use default value for name")
        val swd = koin.get<ServiceWithDefaultValue>()
        println("swd.name: ${swd.name}")
        assert(swd.name == "default_name") { "Expected 'default_name' but got '${swd.name}'" }

        println("ServiceWithMixedParams - should inject A but use defaults for label and count")
        val smp = koin.get<ServiceWithMixedParams>()
        val a = koin.get<A>()
        println("smp.a: ${smp.a}")
        println("smp.label: ${smp.label}")
        println("smp.count: ${smp.count}")
        assert(smp.a == a) { "Expected A to be injected" }
        assert(smp.label == "default_label") { "Expected 'default_label' but got '${smp.label}'" }
        assert(smp.count == 42) { "Expected 42 but got '${smp.count}'" }

        println("Default value tests passed! ✅")
    }
}
