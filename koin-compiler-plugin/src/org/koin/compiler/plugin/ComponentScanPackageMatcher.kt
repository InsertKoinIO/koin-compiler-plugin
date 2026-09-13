package org.koin.compiler.plugin

/**
 * Matches package names against `@ComponentScan` values, using the glob rules in its KDoc.
 * Unlike koin-ksp-compiler, a value never matches a sibling package such as `com.examplex` (#107).
 */
internal object ComponentScanPackageMatcher {

    fun matchesAny(packageName: String, scanPackages: Collection<String>): Boolean {
        if (scanPackages is List<String>) {
            for (i in scanPackages.indices) {
                if (matches(packageName, scanPackages[i])) return true
            }
            return false
        }
        return scanPackages.any { matches(packageName, it) }
    }

    fun matches(packageName: String, scanPackage: String): Boolean =
        if (scanPackage.indexOf('*') >= 0) {
            globMatches(scanPackage, 0, packageName, 0)
        } else {
            packageName.startsWith(scanPackage) &&
                (packageName.length == scanPackage.length || packageName[scanPackage.length] == '.')
        }

    // `*` spans one segment, `**` spans any text, and `name**` adds an optional `.subpackage` tail.
    private fun globMatches(glob: String, globStart: Int, pkg: String, pkgStart: Int): Boolean {
        var g = globStart
        var p = pkgStart
        while (g < glob.length) {
            val c = glob[g]
            if (c != '*') {
                if (p == pkg.length || pkg[p] != c) return false
                g++
                p++
                continue
            }
            if (g + 1 < glob.length && glob[g + 1] == '*') {
                val next = g + 2
                val followsSegmentName = g > 0 && glob[g - 1] != '.'
                val endsSegment = next == glob.length || glob[next] == '.'
                if (followsSegmentName && endsSegment) {
                    if (globMatches(glob, next, pkg, p)) return true
                    if (p == pkg.length || pkg[p] != '.') return false
                    for (k in p + 1..pkg.length) {
                        if (globMatches(glob, next, pkg, k)) return true
                    }
                    return false
                }
                for (k in p..pkg.length) {
                    if (globMatches(glob, next, pkg, k)) return true
                }
                return false
            }
            var k = p
            while (true) {
                if (globMatches(glob, g + 1, pkg, k)) return true
                if (k == pkg.length || pkg[k] == '.') return false
                k++
            }
        }
        return p == pkg.length
    }
}
