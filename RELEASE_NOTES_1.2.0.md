A compile-safety coverage release: Koin's own constructor-shorthand DSL (`singleOf`/`factoryOf`/
`scopedOf`/`viewModelOf`) gets full requirement and qualifier validation for the first time, and a
whole class of cross-module `@Module(includes = [...])`/`@Configuration` visibility gaps — hints
invisible more than one `implementation` dependency hop away — is closed across both the annotation
and DSL paths. Also relaxes the Kotlin-version-compatibility warning to minor-line granularity, and
recognizes two more real Koin entry points: `KoinApplication.withConfiguration<T>()` and Ktor's
`install(Koin) { }`.

## ⚠️ Behavior change — constructor-shorthand DSL is now fully validated

**Before 1.2.0**, `singleOf(::T)`, `factoryOf(::T)`, `scopedOf(::T)`, and `viewModelOf(::T)` were
invisible to compile-time safety entirely — no requirement checking, no qualifier matching. A
missing dependency behind one of these calls compiled clean and only surfaced as a runtime crash.

**As of 1.2.0**, they get the same guarantee as `single<T>()`/`create(::T)`: constructor parameters
are validated as real requirements, and `named()`/`named<T>()` qualifiers on the registration are
matched against consumers. `Scope.new(::T)` (Koin's real runtime function, not code-generated) is
now recognized too. This means **code that compiled silently before may now fail to compile** if it
was relying on an unvalidated `singleOf`/`factoryOf`/`scopedOf`/`viewModelOf` call with a genuinely
missing or misqualified dependency — that's the gap closing, not a regression.

One documented limitation carries over: a hand-written DSL lambda body (`single<T> { someExpr }`,
as opposed to `single<T>()`) has its literal `get()`/`inject()` calls validated as real call sites,
but `KOIN-D004` cycle detection still can't see through it. Narrow and pre-existing, not new.

## 🐛 Fixes — cross-module `@Module(includes = [...])` / `@Configuration` visibility

One root cause, found and fixed across every mechanism that uses it: a cross-module hint is an
ordinary compiled declaration, so a reader can only see one that's on **its own** classpath — a
definition two or more `implementation` (non-transitive) hops away stays invisible even once the
*edge* to it is known. Each of the following closes that gap for one specific path:

- **Annotation `@Module(includes = [...])` edges** (#82): silently dropped 2+ hops away, with no
  hint fallback (unlike every other cross-module annotation discovery path). Fixed with an
  `annotationincludes_*` hint carrier plus relay of the included module's own definitions into the
  includer's compiled hint file.
- **`@Configuration` auto-discovery**: same gap, same fix, for the default label. Custom
  configuration labels are a known narrower remaining gap.
- **DSL `module { includes(...) }`**: same gap for hand-written DSL composition, found via a
  real-world multi-module app. Fixed with definition-hint relaying and de-duplication (a definition
  visible both directly and via relay was being double-reported).
- **Deeper relay bugs found root-causing that same real-world failure**, fixed together: the relay
  only reached one hop instead of the full transitive closure through zero-definition aggregator
  modules; Koin's plural `binds(...)` multi-binding DSL was never recognized at all (only singular
  `bind`), giving every consumer of a `binds()`-bound interface a false `KOIN-D001` at any hop
  distance; and two identically-shaped anonymous DSL definitions batched into one hint file could
  erase to the same JVM method signature, a hard bytecode-load crash rather than a diagnostic.
- **`@Module(includes = [...])` definition dedup ignored qualifiers** (#94): two included modules
  legitimately providing the same type under different qualifiers (or one qualified, one not)
  collapsed into a single dedup key, silently dropping the second provider — a false `KOIN-D001` on
  a graph that resolves correctly at runtime. Now keyed on (type, qualifier) everywhere the
  includes chain is folded.
- **A module including a genuinely-incomplete submodule** (e.g. `@ComponentScan` targeting an empty
  package) was wrongly marked complete, so full-graph validation hard-reported `KOIN-D001` against a
  graph it never fully saw, instead of deferring per the project's fail-open policy. Fixed by
  propagating incompleteness correctly through the includes fold.

## 🐛 Fixes — DSL requirement and qualifier derivation

- **Reified `bind<Interface>()`** (inside `withOptions { }`/`singleOf { }`) silently dropped the
  binding entirely — only the `bind(X::class)` value-argument form was recognized.
- **`create(::function)`** never validated its own referenced function's parameters at all,
  regardless of what was actually computed for it. Definitions built this way are no longer
  excluded from compile-time validation.
- **Hand-written DSL lambda bodies** (`single { X(get<Narrower>()) } bind Y::class`) falsely derived
  requirements from the constructor's declared types instead of the lambda's actual `get()` calls —
  could produce a false `KOIN-D004` self-cycle. Now correctly left unvalidated rather than guessed
  wrong.
- **`startKoin { modules(myList) }`** now resolves stable `List<Module>` compositions (`listOf`,
  `listOfNotNull`, `emptyList`, `arrayOf`, list concatenation, `.toList()`, a function or top-level
  property returning one) instead of blanket-treating them as unverifiable. Genuinely
  runtime-branching cases (`if`/`when`, vararg parameters) correctly stay unresolved.
  `KOIN-W003` now also names the file:line of the entry point's `modules(...)` call.
- **A function returning `Module`** (`fun awsModule(): Module = module { ... }`) is now tracked as a
  composable module, matching what a top-level `val appModule = module { ... }` already got.
  Virtual dispatch is deliberately excluded — an override's declared name doesn't identify which
  body actually runs at the call site.
- DSL-graph validation now correctly **defers** `KOIN-D001`/`KOIN-D002` instead of hard-erroring
  when the entry's module set is already known to be incomplete or unresolvable.

## 🐛 Fixes — cross-module DSL hint encoding

The same requirement-derivation bugs above, found again in the cross-Gradle-module hint
reconstruction path, where a consumer rebuilds a provider's shape purely from what its hint encoded:

- Requirements re-derived from a constructor even when the definition should have carried none —
  same false-`KOIN-D004`-cycle shape as the local-lambda bug above, for cross-module discovery.
- `create(::function)`'s cross-module hint only carried its return type, so a consumer guessed
  requirements from that type's constructor instead of the referenced function's real parameters.
- A qualified requirement (e.g. a qualified `CoroutineDispatcher`) was reconstructed as unqualified
  across a module boundary — a false `KOIN-D001` on a real working app. Hints now encode a
  qualifier alongside each requirement.
- Binding-param filtering switched from a deny-list to an allow-list, so an unrecognized parameter
  kind can never be mistaken for a binding; an unresolvable requirement type no longer silently
  drops just that one requirement while marking the rest complete — it's all-or-nothing.

## ✨ New entry points

- **`KoinApplication.withConfiguration<T>()`** now counts as owning the application's authoritative
  graph — it mutates an already-real, already-installed `KoinApplication` instance (typically
  `startKoin { }`'s own return value, or one threaded in from a shared bootstrap helper), unlike
  `koinApplication { }`/`koinConfiguration { }`'s deliberately isolated fragments. Chained directly
  onto a known isolated instance (`koinApplication { }.withConfiguration<T>()`) correctly stays
  non-authoritative — same reasoning as plain `koinApplication { }`.
- **Ktor's `install(Koin) { modules(...) }`** is now recognized as a real, authoritative entry
  point. It was invisible to compile-safety validation entirely before this — a missing dependency
  wired up through it compiled clean and would only surface as a runtime crash on the first request
  that needed it.

## 🔧 Compatibility — Kotlin version-check relaxed to minor-line granularity

The "newer than tested" warning compared the running compiler's full `major.minor.patch` against
the newest registered adapter, so every new Kotlin patch in an already-supported line (2.4.10,
2.4.20) warned as unverified, even though adapter selection reuses that line's adapter regardless.
Verified via `tools/abi-check` that Kotlin 2.4.10 has zero binary breaks against the plugin's
compiler-API usage.

The warning now fires only when the compiler is on a Kotlin minor line with **no** registered
adapter (e.g. 2.5.0), not on a patch bump within a registered line. Adapter selection itself is
unchanged. This trusts that a new patch within a supported line stays compatible — verify new
patches with `tools/abi-check/check-kotlin-abi.sh <version>` before relying on it for a release.

## ✅ Compatibility

- **Koin**: 4.2.0+
- **Kotlin**: verified range 2.3.20 – 2.4.x

## 📦 Install

```kotlin
plugins {
    id("io.insert-koin.compiler.plugin") version "1.2.0"
}
```

**Full changelog:** https://github.com/InsertKoinIO/koin-compiler-plugin/compare/1.1.0...1.2.0
