package repro.stale

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

class NeverProvided

// Real missing dependency, hidden behind hints the 1.2.1 KLIB never carried.
@Single
class NeedsNeverProvided(val dep: NeverProvided)

@Module
@ComponentScan("repro.stale")
class StaleModule
