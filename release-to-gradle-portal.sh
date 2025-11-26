#!/bin/sh

# Publish Gradle plugin to Gradle Plugin Portal (https://plugins.gradle.org/)
# Run AFTER release.sh and Maven Central approval
./gradlew :koin-compiler-gradle-plugin:publishPlugins --no-configuration-cache
