package crossmodule.app

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module

// @ComponentScan covers the dependency module's package. The two @Single services in
// crossmodule.feature are discovered cross-module via :feature's generated hints. Before the
// dedup fix, each was registered N times — N duplicate top-level hint functions in this module's
// KLIB, which the KLIB serializer rejects on wasmJs / iosArm64 (hard error, invisible on JVM/DEX).
@Module
@Configuration
@ComponentScan("crossmodule.feature")
class NetworkModule
