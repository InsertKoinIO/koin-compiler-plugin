package bench

import bench.gen.AppComponent
import bench.gen.Plugin
import bench.gen.createAndInitialize
import bench.gen.startOnly
import org.koin.core.Koin
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

/**
 * KTZ-4829. `graphCreationAndInitialization` is the exact operation Metro's harness times for
 * Koin: load all 500 generated modules into a detached container, then force realization of every
 * multibinding contributor through getAll. `startOnly` isolates module loading (registration);
 * `warmGetAll` is the multibinding scan alone on an already-built container.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class StartupBenchmark {

    private lateinit var warm: AppComponent

    @Setup(Level.Trial)
    fun setUp() {
        warm = createAndInitialize()
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        warm.koin.close()
    }

    @Benchmark
    fun graphCreationAndInitialization(blackhole: Blackhole) {
        blackhole.consume(createAndInitialize())
    }

    @Benchmark
    fun startOnly(blackhole: Blackhole) {
        val koin: Koin = startOnly()
        blackhole.consume(koin)
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    fun warmGetAll(blackhole: Blackhole) {
        blackhole.consume(warm.koin.getAll<Plugin>().size)
    }
}
