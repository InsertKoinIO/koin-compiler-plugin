// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// 1.1.0 — A2 (and its KOIN-W002 deferral mechanism, KTZ-4256 / GH #51) is gone; this compilation
// now emits NO diagnostic at all (empty golden). Kept under its original name for history.
//
// A library module compiles two sibling @Module classes with NO Koin entry point in this
// compilation (no startKoin / @KoinApplication / koinApplication). FeatureModule.service() needs
// Repository, which is genuinely provided on the build graph by the sibling DataModule. A2 used to
// defer this as KOIN-W002 (a real provider exists somewhere, but no closure here proves the
// assembled graph complete). A3 is now the sole verifier and only runs at an entry point — no
// entry point here means generation only (see CompileSafetyValidator's class doc). The downstream
// app that actually assembles [DataModule, FeatureModule] is where this gets verified.
//
// Contrast: cross_module_sibling_koinapp_ok (same providers + a complete @KoinApplication closure
// → resolved at A3, zero diagnostics) and cross_module_genuine_missing_d001 (no provider anywhere
// → hard D001 at the entry point, never silent).
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.core.annotation.Factory

class Repository

class Service(val repo: Repository)

// DataModule provides Repository — the provider hint that makes FeatureModule's miss a deferral.
@Module
class DataModule {
    @Single
    fun repository(): Repository = Repository()
}

// FeatureModule needs Repository from the sibling DataModule. No entry point assembles them here.
@Module
class FeatureModule {
    @Factory
    fun service(repo: Repository): Service = Service(repo)
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, primaryConstructor,
   propertyDeclaration */
