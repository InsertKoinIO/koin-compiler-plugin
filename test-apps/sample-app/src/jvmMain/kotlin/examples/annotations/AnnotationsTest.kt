package examples.annotations

import androidx.lifecycle.ViewModel
import examples.annotations.other.MySecondModule
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Factory
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.Named
import org.koin.core.annotation.Scope
import org.koin.core.annotation.Scoped
import org.koin.core.annotation.Singleton

@Module(includes = [MySecondModule::class]) // generate module declaration extension val MyModule.module get() : Module = module { }
@ComponentScan // scan for annotations in current package
class MyModule {

    @Singleton
    fun myFunBuilder(a: A, d: D? = null) = FunBuilder(a, d)

    @Factory
    fun myFunLazyBuilder(a: A, b: Lazy<B>) = FunLazyBuilder(a, b)
}

@Singleton
class A

@Singleton
class B(val a : A)

@Singleton
class C(val b: B, val a: Lazy<A>)

class D()

@Singleton
class E(val d: D? = null)

@Factory
class F(val a: A)  // Use local A, not examples.A

@Factory
class G(val b: B)  // Use local B, not examples.B

// Function builder (using local types)
class FunBuilder(val a: A, val d: D? = null)
class FunLazyBuilder(val a: A, val b: Lazy<B>)

interface MyInterface

@Singleton
class MyInterfaceImpl(val a: A) : MyInterface  // Use local A

@Singleton
@Named("dumb")
class MyInterfaceDumb(val a: A) : MyInterface  // Use local A

@Singleton
class DumbConsumer(@Named("dumb") val i: MyInterfaceDumb)

@Factory
class MyClassWithParam(@InjectedParam val i: Int)

@KoinViewModel
class MyViewModel(val a: A, val b: B) : ViewModel()  // Use local types

// scope
class MyScope

@Scope(MyScope::class) // will associate scope<MyScope> { } section in module
@Scoped// will generated scoped(::T) definition
class MyScopedStuff

// @KoinApplication generates startKoin() and koinApplication() extension functions
// It auto-discovers and loads all @Module @ComponentScan classes in the compilation
@KoinApplication(modules = [MyModule::class])
object MyApp