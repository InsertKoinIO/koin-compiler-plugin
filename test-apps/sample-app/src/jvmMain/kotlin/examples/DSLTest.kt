package examples

import androidx.lifecycle.ViewModel
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.Named
import org.koin.core.annotation.Qualifier

class A()
class A1(val a1: String)
class A1Nullable(val a1: String? = null)
class A1Lazy(val a1: Lazy<String>)

class B(val a: A)
class C(val b: B, val a: Lazy<A>)
class D()
class E(val d: D? = null)
class F(val a: A)
class G(val b: B)
class G2(val b: B)

class H(val a: A, val b: B)
class I(val a: A, val d: D? = null)

// Function builder
class FunBuilder(val a: A, val d: D? = null)

// safely call function, instead of anonymous lambda -> no need of get<examples.A>() or getOrNull<examples.D>()
fun myFunBuilder(a: A, d: D? = null) = FunBuilder(a, d)
class FunLazyBuilder(val a: A, val b: Lazy<B>)

fun myFunLazyBuilder(a: A, b: Lazy<B>) = FunLazyBuilder(a, b)

interface MyInterface
class MyInterfaceImpl(val a: A) : MyInterface

@Named("dumb")
class MyInterfaceDumb(val a: A) : MyInterface
class DumbConsumer(@Named("dumb") val i: MyInterfaceDumb)

// @Qualifier with name (string-based qualifier)
@Qualifier(name = "qualified")
class QualifiedService(val a: A)
class QualifiedConsumer(@Qualifier(name = "qualified") val service: QualifiedService)

// @Qualifier with type (type-based qualifier)
interface QualifierType  // Marker interface used as type qualifier
@Qualifier(QualifierType::class)
class TypeQualifiedService(val a: A)
class TypeQualifiedConsumer(@Qualifier(QualifierType::class) val service: TypeQualifiedService)

class MyClassWithParam(@InjectedParam val i: Int)

// ViewModel test
class MyViewModel(val a: A, val b: B) : ViewModel()
class MyViewModel2(val a: A, val b: B) : ViewModel()