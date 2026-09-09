package crossmodule.app

// Gives :app's wasmJs compilation a second source fragment (commonMain + wasmJsMain), so the
// consumer side of the relay path is exercised with more than one fragment rather than only
// commonMain.
fun wasmEntryMarker(): String = "wasm"
