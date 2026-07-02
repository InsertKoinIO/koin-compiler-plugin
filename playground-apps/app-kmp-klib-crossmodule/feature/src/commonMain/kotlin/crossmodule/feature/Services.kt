package crossmodule.feature

import org.koin.core.annotation.Single

// Bare @Single classes in a dependency module. :app's @ComponentScan("crossmodule.feature")
// discovers these via the definition hints generated here — the cross-module path that
// duplicated registrations before the fix.

@Single
class CharactersApiService

@Single
class EpisodesApiService

// #62 cross-module probe: a TOP-LEVEL @Single function in the dependency module. Top-level
// functions use a separate cross-module discovery path (definition_function_* hints /
// findMatchingTopLevelFunctions) than classes — verify it is discovered by :app's cross-module
// @ComponentScan without duplicating (klib clash) or being silently dropped.
class ApiConfig

@Single
fun provideApiConfig(): ApiConfig = ApiConfig()
