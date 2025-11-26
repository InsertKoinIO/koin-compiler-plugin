package examples.configlabels.prodpkg

import org.koin.core.annotation.Singleton

@Singleton
class ProdService {
    val name: String = "ProdService (prod config)"
}
