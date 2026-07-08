// RUN_PIPELINE_TILL: BACKEND
// FILE: test.kt
// KTZ-4256 / GH #51 — deferred → KOIN-W002 (library compilation, no entry point).
//
// A library module compiles two sibling @Module classes with NO Koin entry point in this
// compilation (no startKoin / @KoinApplication / koinApplication). FeatureModule.service() needs
// Repository, which is genuinely provided on the build graph by the sibling DataModule — so a
// provider hint for Repository DOES exist. The KTZ-4256 discriminator keys off provider-hint
// EXISTENCE: because a provider exists somewhere, FeatureModule's unresolved-in-isolation Repository
// is a real cross-module dep (DataModule isn't in FeatureModule's own A2 visibility set — the two are
// wired only downstream at the consuming app's @KoinApplication), so it is DEFERRED rather than
// hard-errored.
//
// There is no complete closed closure in THIS compilation to settle the deferral (the app that
// assembles [DataModule, FeatureModule] lives downstream). So the deferral is flushed as the
// KOIN-W002 warning — deferred, not an error — validated authoritatively at the downstream entry
// point / runtime checkModules(). This is the shape that legitimately yields W002: a real provider
// exists, but no closure here proves the assembled graph complete.
//
// Contrast: cross_module_sibling_koinapp_ok (same providers + a complete @KoinApplication closure →
// deferral SETTLED at A3, zero diagnostics) and cross_module_genuine_missing_d001 (no provider hint
// anywhere → hard D001, never deferred).
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
