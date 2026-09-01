package org.koin.sample.analytics.di

import org.koin.dsl.bind
import org.koin.dsl.module
import org.koin.sample.analytics.AnalyticsHelper
import org.koin.sample.analytics.StubAnalyticsHelper

val analyticsModule = module {
    single { StubAnalyticsHelper() } bind AnalyticsHelper::class
}
