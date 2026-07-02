package crossmodule.feature

import org.koin.core.annotation.Single

// Bare @Single classes in a dependency module. :app's @ComponentScan("crossmodule.feature")
// discovers these via the definition hints generated here — the cross-module path that
// duplicated registrations before the fix.

@Single
class CharactersApiService

@Single
class EpisodesApiService
