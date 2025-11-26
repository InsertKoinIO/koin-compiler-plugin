apply(plugin = "maven-publish")

// Note: com.gradle.plugin-publish 1.2.1 automatically adds sources and javadoc jars

afterEvaluate {
    configure<PublishingExtension> {
        publications {
            // java-gradle-plugin creates publications automatically
            // Add POM metadata to all of them
            withType<MavenPublication> {
                pom {
                    name.set("Koin Compiler Gradle Plugin")
                    description.set("Gradle plugin for Koin compiler plugin integration")
                    url.set("https://insert-koin.io/")

                    licenses {
                        license {
                            name.set("The Apache Software License, Version 2.0")
                            url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }

                    scm {
                        url.set("https://github.com/InsertKoinIO/koin-compiler-plugin")
                        connection.set("scm:git:https://github.com/InsertKoinIO/koin-compiler-plugin.git")
                    }

                    developers {
                        developer {
                            name.set("Arnaud Giuliani")
                            email.set("arnaud@kotzilla.io")
                        }
                    }
                }
            }
        }
    }
}

apply(from = rootProject.file("gradle/signing.gradle.kts"))
