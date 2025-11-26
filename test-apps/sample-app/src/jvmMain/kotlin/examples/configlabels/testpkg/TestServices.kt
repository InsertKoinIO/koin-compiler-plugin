package examples.configlabels.testpkg

import org.koin.core.annotation.Singleton

@Singleton
class TestService {
    val name: String = "TestService (test config)"
}
