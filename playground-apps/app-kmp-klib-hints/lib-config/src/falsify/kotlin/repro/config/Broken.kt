package repro.config

import org.koin.core.annotation.Single

class NeverProvided

// Real missing dependency: NeverProvided has no definition anywhere.
@Single
class NeedsNeverProvided(val dep: NeverProvided)
