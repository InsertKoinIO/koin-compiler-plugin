// @ComponentScan glob patterns select packages the same way as KSP, without matching sibling packages (#107)
// FILE: exact.kt
package glob.exact.a

import org.koin.core.annotation.Single

@Single
class ExactSub

// FILE: exactSibling.kt
package glob.exactx

import org.koin.core.annotation.Single

@Single
class ExactSibling

// FILE: deepZero.kt
package glob.deep.infra

import org.koin.core.annotation.Single

@Single
class DeepZero

// FILE: deepOne.kt
package glob.deep.a.infra

import org.koin.core.annotation.Single

@Single
class DeepOne

// FILE: deepTwo.kt
package glob.deep.a.b.infra

import org.koin.core.annotation.Single

@Single
class DeepTwo

// FILE: deepBelow.kt
package glob.deep.a.infra.sub

import org.koin.core.annotation.Single

@Single
class DeepBelow

// FILE: subRoot.kt
package glob.sub

import org.koin.core.annotation.Single

@Single
class SubRoot

// FILE: subOne.kt
package glob.sub.a

import org.koin.core.annotation.Single

@Single
class SubOne

// FILE: subTwo.kt
package glob.sub.a.b

import org.koin.core.annotation.Single

@Single
class SubTwo

// FILE: rootSelf.kt
package glob.root

import org.koin.core.annotation.Single

@Single
class RootSelf

// FILE: rootSub.kt
package glob.root.a

import org.koin.core.annotation.Single

@Single
class RootSub

// FILE: rootSibling.kt
package glob.rootx

import org.koin.core.annotation.Single

@Single
class RootSibling

// FILE: singleZero.kt
package glob.single.svc

import org.koin.core.annotation.Single

@Single
class SingleZero

// FILE: singleOne.kt
package glob.single.a.svc

import org.koin.core.annotation.Single

@Single
class SingleOne

// FILE: singleTwo.kt
package glob.single.a.b.svc

import org.koin.core.annotation.Single

@Single
class SingleTwo

// FILE: comboSuffix.kt
package glob.combo.a.service.userdata

import org.koin.core.annotation.Single

@Single
class ComboSuffix

// FILE: comboSplit.kt
package glob.combo.a.service.user.data

import org.koin.core.annotation.Single

@Single
class ComboSplit

// FILE: function.kt
package glob.fn.a.infra

import org.koin.core.annotation.Single

class FnRepo

@Single
fun provideFnRepo(): FnRepo = FnRepo()

// FILE: test.kt
package app

import glob.combo.a.service.user.data.ComboSplit
import glob.combo.a.service.userdata.ComboSuffix
import glob.deep.a.b.infra.DeepTwo
import glob.deep.a.infra.DeepOne
import glob.deep.a.infra.sub.DeepBelow
import glob.deep.infra.DeepZero
import glob.exact.a.ExactSub
import glob.exactx.ExactSibling
import glob.fn.a.infra.FnRepo
import glob.root.RootSelf
import glob.root.a.RootSub
import glob.rootx.RootSibling
import glob.single.a.b.svc.SingleTwo
import glob.single.a.svc.SingleOne
import glob.single.svc.SingleZero
import glob.sub.SubRoot
import glob.sub.a.SubOne
import glob.sub.a.b.SubTwo
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.dsl.koinApplication

@Module
@ComponentScan("glob.exact")
class ExactModule

@Module
@ComponentScan("glob.deep.**.infra")
class DeepModule

@Module
@ComponentScan("glob.sub.**")
class SubModule

@Module
@ComponentScan("glob.root**")
class RootModule

@Module
@ComponentScan("glob.single.*.svc")
class SingleModule

@Module
@ComponentScan("glob.combo.**.service.*data")
class ComboModule

@Module
@ComponentScan("glob.fn.**.infra")
class FnModule

fun box(): String {
    val failures = mutableListOf<String>()
    fun expect(case: String, resolved: Boolean, expected: Boolean) {
        if (resolved != expected) failures += "$case resolved=$resolved"
    }

    val exact = koinApplication { modules(ExactModule().module()) }.koin
    expect("glob.exact -> glob.exact.a", exact.getOrNull<ExactSub>() != null, true)
    expect("glob.exact -> glob.exactx", exact.getOrNull<ExactSibling>() != null, false)

    val deep = koinApplication { modules(DeepModule().module()) }.koin
    expect("glob.deep.**.infra -> glob.deep.infra", deep.getOrNull<DeepZero>() != null, false)
    expect("glob.deep.**.infra -> glob.deep.a.infra", deep.getOrNull<DeepOne>() != null, true)
    expect("glob.deep.**.infra -> glob.deep.a.b.infra", deep.getOrNull<DeepTwo>() != null, true)
    expect("glob.deep.**.infra -> glob.deep.a.infra.sub", deep.getOrNull<DeepBelow>() != null, false)

    val sub = koinApplication { modules(SubModule().module()) }.koin
    expect("glob.sub.** -> glob.sub", sub.getOrNull<SubRoot>() != null, false)
    expect("glob.sub.** -> glob.sub.a", sub.getOrNull<SubOne>() != null, true)
    expect("glob.sub.** -> glob.sub.a.b", sub.getOrNull<SubTwo>() != null, true)

    val root = koinApplication { modules(RootModule().module()) }.koin
    expect("glob.root** -> glob.root", root.getOrNull<RootSelf>() != null, true)
    expect("glob.root** -> glob.root.a", root.getOrNull<RootSub>() != null, true)
    expect("glob.root** -> glob.rootx", root.getOrNull<RootSibling>() != null, false)

    val single = koinApplication { modules(SingleModule().module()) }.koin
    expect("glob.single.*.svc -> glob.single.svc", single.getOrNull<SingleZero>() != null, false)
    expect("glob.single.*.svc -> glob.single.a.svc", single.getOrNull<SingleOne>() != null, true)
    expect("glob.single.*.svc -> glob.single.a.b.svc", single.getOrNull<SingleTwo>() != null, false)

    val combo = koinApplication { modules(ComboModule().module()) }.koin
    expect("glob.combo.**.service.*data -> glob.combo.a.service.userdata", combo.getOrNull<ComboSuffix>() != null, true)
    expect("glob.combo.**.service.*data -> glob.combo.a.service.user.data", combo.getOrNull<ComboSplit>() != null, false)

    val fn = koinApplication { modules(FnModule().module()) }.koin
    expect("glob.fn.**.infra -> fun in glob.fn.a.infra", fn.getOrNull<FnRepo>() != null, true)

    return if (failures.isEmpty()) "OK" else "FAIL: $failures"
}
