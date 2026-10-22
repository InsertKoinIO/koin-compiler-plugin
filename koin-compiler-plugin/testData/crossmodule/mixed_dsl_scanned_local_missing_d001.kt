package repro.local

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.context.startKoin
import org.koin.plugin.module.dsl.modules

class Missing
class Scanned @javax.inject.Inject constructor(val missing: Missing)

@Module
@ComponentScan("repro.local")
class ComponentsModule

fun start() = startKoin {
    modules(ComponentsModule::class)
}

/* GENERATED_FIR_TAGS: classDeclaration, classReference, functionDeclaration, lambdaLiteral, primaryConstructor,
propertyDeclaration, stringLiteral */
