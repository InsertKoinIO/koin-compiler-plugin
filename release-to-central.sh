#!/bin/sh

# Publish to Maven Central (requires manual release on https://central.sonatype.com/)
./gradlew publishAggregationToCentralPortal --no-configuration-cache
