package playground

import org.koin.core.context.startKoin
import org.koin.dsl.bind
import org.koin.dsl.module

interface Greeter {
    fun greet(): String
}

class Repository {
    fun source() = "koin"
}

class Service(private val repository: Repository) : Greeter {
    override fun greet() = "hello from ${repository.source()}"
}

class Counter {
    var count = 0
}

val appModule = module {
    single { Repository() }
    single { Service(get()) } bind Greeter::class
    factory { Counter() }
}

fun main() {
    val koin = startKoin {
        modules(appModule)
    }.koin

    println(koin.get<Greeter>().greet())
}
