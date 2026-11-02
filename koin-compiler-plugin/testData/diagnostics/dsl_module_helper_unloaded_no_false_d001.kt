// RUN_PIPELINE_TILL: BACKEND
// Single compilation: a `fun Module.helper()` called only from a module the entry point
// never loads. Its definitions are unreachable: no KOIN-D001 for their missing dependency.

import org.koin.core.module.Module
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import org.koin.plugin.module.dsl.single

class Links
class TicketStore
class Invoicing(val store: TicketStore)

val alertModule = module {
    single<Links>()
}

val invoicingModule = module {
    connectionBeans()
}

private fun Module.connectionBeans() {
    single<Invoicing>()
}

fun main() {
    koinApplication { modules(alertModule) }
}

/* GENERATED_FIR_TAGS: classDeclaration, funWithExtensionReceiver, functionDeclaration, lambdaLiteral,
primaryConstructor, propertyDeclaration */
