package com.alsaed

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AlsaedPlugin : Plugin() {
    override fun load() {
        registerMainAPI(Alsaed())
    }
}
