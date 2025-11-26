package examples.configlabels.defaultpkg

import org.koin.core.annotation.Singleton

@Singleton
class DefaultService {
    val name: String = "DefaultService (default config)"
}
