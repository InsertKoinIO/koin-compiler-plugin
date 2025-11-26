package examples.annotations.scan

import org.koin.core.annotation.Singleton

@Singleton
class C2

@Singleton
class D2(val c : C2)