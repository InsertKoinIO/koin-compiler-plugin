// Cross-module @ComponentScan glob patterns select dependency packages the same way as KSP, without matching sibling packages (#107)

// MODULE: lib
// FILE: exact.kt
package lib.exact.a

import org.koin.core.annotation.Single

@Single
class LibExact

// FILE: exactSibling.kt
package lib.exactx

import org.koin.core.annotation.Single

@Single
class LibExactSibling

// FILE: deepOne.kt
package lib.deep.a.infra

import org.koin.core.annotation.Single

@Single
class LibDeepOne

// FILE: deepBelow.kt
package lib.deep.a.infra.sub

import org.koin.core.annotation.Single

@Single
class LibDeepBelow

// FILE: root.kt
package lib.root

import org.koin.core.annotation.Single

@Single
class LibRoot

// FILE: rootSibling.kt
package lib.rootx

import org.koin.core.annotation.Single

@Single
class LibRootSibling

// FILE: singleOne.kt
package lib.single.a.svc

import org.koin.core.annotation.Single

@Single
class LibSingleOne

// FILE: singleTwo.kt
package lib.single.a.b.svc

import org.koin.core.annotation.Single

@Single
class LibSingleTwo

// MODULE: app(lib)
// FILE: test.kt
package app

import lib.deep.a.infra.LibDeepOne
import lib.deep.a.infra.sub.LibDeepBelow
import lib.exact.a.LibExact
import lib.exactx.LibExactSibling
import lib.root.LibRoot
import lib.rootx.LibRootSibling
import lib.single.a.b.svc.LibSingleTwo
import lib.single.a.svc.LibSingleOne
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.dsl.koinApplication

@Module
@ComponentScan("lib.exact")
class LibExactModule

@Module
@ComponentScan("lib.deep.**.infra")
class LibDeepModule

@Module
@ComponentScan("lib.root**")
class LibRootModule

@Module
@ComponentScan("lib.single.*.svc")
class LibSingleModule

fun box(): String {
    val failures = mutableListOf<String>()
    fun expect(case: String, resolved: Boolean, expected: Boolean) {
        if (resolved != expected) failures += "$case resolved=$resolved"
    }

    val exact = koinApplication { modules(LibExactModule().module()) }.koin
    expect("lib.exact -> lib.exact.a", exact.getOrNull<LibExact>() != null, true)
    expect("lib.exact -> lib.exactx", exact.getOrNull<LibExactSibling>() != null, false)

    val deep = koinApplication { modules(LibDeepModule().module()) }.koin
    expect("lib.deep.**.infra -> lib.deep.a.infra", deep.getOrNull<LibDeepOne>() != null, true)
    expect("lib.deep.**.infra -> lib.deep.a.infra.sub", deep.getOrNull<LibDeepBelow>() != null, false)

    val root = koinApplication { modules(LibRootModule().module()) }.koin
    expect("lib.root** -> lib.root", root.getOrNull<LibRoot>() != null, true)
    expect("lib.root** -> lib.rootx", root.getOrNull<LibRootSibling>() != null, false)

    val single = koinApplication { modules(LibSingleModule().module()) }.koin
    expect("lib.single.*.svc -> lib.single.a.svc", single.getOrNull<LibSingleOne>() != null, true)
    expect("lib.single.*.svc -> lib.single.a.b.svc", single.getOrNull<LibSingleTwo>() != null, false)

    return if (failures.isEmpty()) "OK" else "FAIL: $failures"
}
