package examples.configlabels.sharedpkg

import org.koin.core.annotation.Singleton

@Singleton
class SharedService {
    val name: String = "SharedService (test & prod config)"
}
