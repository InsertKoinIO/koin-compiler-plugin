package crossmodule.platformscan

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module

// Coverage for a shape the KLIB gates previously had none of: this @Module lives in commonMain,
// but the class its @ComponentScan discovers lives in a PLATFORM source set (wasmJsMain). :app
// never scans this package; it reaches this module through @Configuration auto-discovery, which
// relays this module's own definition hints into :app's KLIB.
//
// Added while chasing a duplicate-hint KLIB clash that a real KMP app hit on this shape. It does
// NOT on its own reproduce that clash (the real case needed a route this playground could not be
// coaxed into), so treat it as coverage of the relay path, not as that bug's regression test.
@Module
@Configuration
@ComponentScan("crossmodule.platformscan")
class PlatformScanModule
