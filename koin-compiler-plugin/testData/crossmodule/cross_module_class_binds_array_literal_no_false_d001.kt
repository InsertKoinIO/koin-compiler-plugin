// RUN_PIPELINE_TILL: BACKEND
// GH #98: an explicit `@Single(binds = [Controller::class])` on a class scanned from a
// dependency must keep that binding cross-module (not fall back to the direct supertype only).
//
// EXPECTED: no diagnostics (Controller is provided by NodeController).

// MODULE: feature
// FILE: example/feature/Feature.kt
package example.feature

import org.koin.core.annotation.Single

interface Controller
interface CrudController<E> : Controller
interface GraphController<E> : CrudController<E>
data class Node(val id: Long)

@Single(binds = [Controller::class])
class NodeController : GraphController<Node>

// MODULE: app(feature)
// FILE: example/app/App.kt
package example.app

import example.feature.Controller
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.KoinApplication
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.plugin.module.dsl.startKoin

@Single
class UsesController(val controller: Controller)

@Module
@ComponentScan("example")
class AppModule

@KoinApplication(modules = [AppModule::class])
object AppGraph

fun main() {
    startKoin<AppGraph> {}
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, collectionLiteral, data, functionDeclaration,
interfaceDeclaration, lambdaLiteral, nullableType, objectDeclaration, primaryConstructor, propertyDeclaration,
stringLiteral, typeParameter */
