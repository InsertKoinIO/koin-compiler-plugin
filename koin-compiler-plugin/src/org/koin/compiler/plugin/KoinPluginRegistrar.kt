package org.koin.compiler.plugin

import org.koin.compiler.plugin.fir.KoinModuleFirGenerator
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar

class KoinPluginRegistrar : FirExtensionRegistrar() {
    override fun ExtensionRegistrarContext.configurePlugin() {
        +::KoinModuleFirGenerator
    }
}
