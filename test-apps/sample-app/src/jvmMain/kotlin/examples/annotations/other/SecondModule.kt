package examples.annotations.other

import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Module
import org.koin.core.annotation.Singleton

@Module // generate module declaration extension val MyModule.module get() : Module = module { }
@ComponentScan("examples.annotations.other","examples.annotations.scan") // scan for annotations in current package
class MySecondModule

@Singleton
class A2

@Singleton
class B2(val a : A2)
