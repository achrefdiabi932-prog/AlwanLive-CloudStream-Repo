package com.alwanlive

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AlwanLivePlugin : Plugin() {
    override fun load() {
        registerMainAPI(AlwanLive())
    }
}
