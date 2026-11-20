package com.majedkoora

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MajedKooraPlugin : Plugin() {
    override fun load() {
        registerMainAPI(MajedKoora())
    }
}
