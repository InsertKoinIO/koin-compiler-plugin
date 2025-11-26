// maven-publish is applied in the main build.gradle.kts plugins block

val sourcesJar by tasks.registering(Jar::class) {
    archiveClassifier.set("sources")
    from(project.the<SourceSetContainer>()["main"].allSource)
}

val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier.set("javadoc")
}

configure<PublishingExtension> {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifact(sourcesJar)
            artifact(javadocJar)

            artifactId = "koin-compiler-plugin"

            pom {
                name.set("Koin Compiler Plugin")
                description.set("Kotlin compiler plugin for Koin dependency injection - compile-time DSL transformation")
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

apply(from = rootProject.file("gradle/signing.gradle.kts"))
