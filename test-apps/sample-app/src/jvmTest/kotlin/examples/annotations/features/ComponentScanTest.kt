package examples.annotations.features

import examples.annotations.features.sub.SubpackageRepository
import examples.annotations.features.sub.SubpackageService
import examples.isolated.IsolatedClassNotScanned
import examples.isolated.IsolatedClassScanned
import examples.isolated.IsolatedFunctionService
import examples.isolated.IsolatedNoScanApp
import examples.isolated.IsolatedScanApp
import examples.isolated.sub.IsolatedSubpackageRepository
import examples.isolated.sub.IsolatedSubpackageService
import org.junit.After
import org.junit.Test
import org.koin.core.context.stopKoin
import org.koin.core.logger.Level
import org.koin.plugin.module.dsl.startKoin
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for @ComponentScan package scanning behavior.
 *
 * @ComponentScan defines which packages to scan for annotated classes:
 * - No arguments: scan current package and all sub-packages
 * - With arguments: scan specified package(s) and their sub-packages
 * - Scanning is recursive: io.koin scans io.koin.**, io.koin.feature1.**, etc.
 *
 * NOTE: Tests use isolated package (examples.isolated) to avoid interference
 * from other @Configuration modules that auto-discover.
 */
class ComponentScanTest {

    @After
    fun tearDown() {
        stopKoin()
    }

    /**
     * Test 1: @Module without @ComponentScan
     *
     * IsolatedNoScanModule has NO @ComponentScan annotation, so:
     * - Function-based definitions (@Singleton fun provide...()) SHOULD work
     * - Class-based definitions (@Singleton class X) should NOT be scanned
     */
    @Test
    fun `module without ComponentScan only has function definitions`() {
        val koin = startKoin<IsolatedNoScanApp> {
            printLogger(Level.DEBUG)
        }.koin

        // Function-based definition SHOULD be available
        val functionService = koin.getOrNull<IsolatedFunctionService>()
        assertNotNull(functionService, "Function-based definition should be available")
        println("IsolatedFunctionService: $functionService")

        // Class-based definition should NOT be available (no @ComponentScan)
        val classNotScanned = koin.getOrNull<IsolatedClassNotScanned>()
        assertNull(classNotScanned, "IsolatedClassNotScanned should NOT be in module (no @ComponentScan)")
        println("IsolatedClassNotScanned correctly not found in IsolatedNoScanModule")

        println("\n@Module without @ComponentScan: only function definitions work")
    }

    /**
     * Test 2: @Module @ComponentScan (no arguments)
     *
     * IsolatedScanModule has @ComponentScan without arguments, so:
     * - Scans current package (examples.isolated)
     * - Scans ALL sub-packages (examples.isolated.sub, etc.)
     */
    @Test
    fun `module with ComponentScan scans current package`() {
        val koin = startKoin<IsolatedScanApp> {
            printLogger(Level.DEBUG)
        }.koin

        // Class in current package SHOULD be available
        val classScanned = koin.getOrNull<IsolatedClassScanned>()
        assertNotNull(classScanned, "IsolatedClassScanned should be scanned (same package)")
        println("IsolatedClassScanned: $classScanned")

        // Singleton - same instance each time
        val classScanned2 = koin.get<IsolatedClassScanned>()
        assertEquals(classScanned, classScanned2, "Singleton should return same instance")

        println("\n@Module @ComponentScan (no args): scans current package")
    }

    /**
     * Test 3: Recursive subpackage scanning
     *
     * @ComponentScan on examples.isolated should also scan
     * examples.isolated.sub (and any deeper subpackages)
     */
    @Test
    fun `ComponentScan recursively scans subpackages`() {
        val koin = startKoin<IsolatedScanApp> {
            printLogger(Level.DEBUG)
        }.koin

        // Classes in subpackage SHOULD be available (recursive scanning)
        val subService = koin.getOrNull<IsolatedSubpackageService>()
        assertNotNull(subService, "IsolatedSubpackageService should be scanned (subpackage)")
        println("IsolatedSubpackageService: $subService")

        // IsolatedSubpackageRepository is @Factory, should create new instances
        val repo1 = koin.getOrNull<IsolatedSubpackageRepository>()
        assertNotNull(repo1, "IsolatedSubpackageRepository should be scanned (subpackage)")
        println("IsolatedSubpackageRepository: $repo1")

        val repo2 = koin.get<IsolatedSubpackageRepository>()
        assertNotEquals(repo1, repo2, "Factory should create new instances")

        // IsolatedSubpackageRepository depends on IsolatedSubpackageService
        assertEquals(subService, repo1.service, "Factory should inject singleton")
        assertEquals(subService, repo2.service, "Factory should inject same singleton")

        println("\n@ComponentScan recursive scanning: subpackages are included")
    }

    /**
     * Test 4: Multiple package scanning
     *
     * MySecondModule uses @ComponentScan("examples.annotations.other", "examples.annotations.scan")
     * This tests scanning multiple specific packages.
     */
    @Test
    fun `ComponentScan with multiple packages scans all specified`() {
        val koin = startKoin<MultiPackageScanApp> {
            printLogger(Level.DEBUG)
        }.koin

        // Classes from examples.annotations.other
        val a2 = koin.getOrNull<examples.annotations.other.A2>()
        assertNotNull(a2, "A2 should be scanned from examples.annotations.other")
        println("A2: $a2")

        val b2 = koin.getOrNull<examples.annotations.other.B2>()
        assertNotNull(b2, "B2 should be scanned from examples.annotations.other")
        println("B2: $b2")
        assertEquals(a2, b2.a, "B2 should inject singleton A2")

        // Classes from examples.annotations.scan
        val c2 = koin.getOrNull<examples.annotations.scan.C2>()
        assertNotNull(c2, "C2 should be scanned from examples.annotations.scan")
        println("C2: $c2")

        val d2 = koin.getOrNull<examples.annotations.scan.D2>()
        assertNotNull(d2, "D2 should be scanned from examples.annotations.scan")
        println("D2: $d2")
        assertEquals(c2, d2.c, "D2 should inject singleton C2")

        println("\n@ComponentScan('pkg1', 'pkg2'): scans all specified packages")
    }

    /**
     * Test 5: Contrast - class available via scanning module but not via no-scan module
     *
     * Demonstrates that the SAME class (IsolatedClassNotScanned) can be:
     * - NOT available when using IsolatedNoScanModule (no @ComponentScan)
     * - Available when using IsolatedScanModule (has @ComponentScan)
     */
    @Test
    fun `same class available via scan module but not via no-scan module`() {
        // Test with no-scan module first
        val koin1 = startKoin<IsolatedNoScanApp> {
            printLogger(Level.DEBUG)
        }.koin

        val notScanned = koin1.getOrNull<IsolatedClassNotScanned>()
        assertNull(notScanned, "Should NOT be available via IsolatedNoScanModule")
        println("IsolatedClassNotScanned via NoScanModule: null (correct)")

        stopKoin()

        // Test with scan module
        val koin2 = startKoin<IsolatedScanApp> {
            printLogger(Level.DEBUG)
        }.koin

        val scanned = koin2.getOrNull<IsolatedClassNotScanned>()
        assertNotNull(scanned, "SHOULD be available via IsolatedScanModule")
        println("IsolatedClassNotScanned via ScanModule: $scanned (correct)")

        println("\nSame class, different modules: @ComponentScan determines visibility")
    }
}
