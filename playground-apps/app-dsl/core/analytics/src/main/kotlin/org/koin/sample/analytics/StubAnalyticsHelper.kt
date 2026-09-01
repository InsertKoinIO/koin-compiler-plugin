package org.koin.sample.analytics

import android.util.Log

// Simulate missing definition with classic DSL
//class NewStuff()
//class StubAnalyticsHelper(val ns : NewStuff) : AnalyticsHelper {

class StubAnalyticsHelper : AnalyticsHelper {
    override fun logEvent(name: String, params: Map<String, String>) {
        Log.d("Analytics", "Event: $name, params: $params")
    }

    override fun setUserProperty(name: String, value: String) {
        Log.d("Analytics", "UserProperty: $name = $value")
    }
}
