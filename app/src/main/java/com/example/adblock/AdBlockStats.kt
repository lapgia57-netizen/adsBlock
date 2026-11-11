package com.example.adblock

import kotlinx.coroutines.flow.MutableStateFlow

object AdBlockStats {
    val blocked = MutableStateFlow(0L)
    val running = MutableStateFlow(false)
}
