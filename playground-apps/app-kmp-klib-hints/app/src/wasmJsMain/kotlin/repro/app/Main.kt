package repro.app

// Runtime proof: every cross-module definition the KLIB hints describe actually resolves.
fun main() {
    initKoin()
    println("counter=${counter()} clock=${clock()} local=${local()} client=${org.koin.mp.KoinPlatform.getKoin().get<Client>().logger}")
    println("KLIB-HINTS-RUNTIME-OK")
}
