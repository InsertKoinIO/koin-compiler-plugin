package crossmodule.platformscan

import org.koin.core.annotation.Single

interface PlatformNotifier

// Annotated class with an EXPLICIT binding, declared in a platform source set of the module
// whose @ComponentScan (in commonMain) discovers it.
@Single([PlatformNotifier::class])
class WasmPlatformNotifier : PlatformNotifier
