package com.landosol.toolbox.labyrinth.debug

import com.landosol.toolbox.BuildConfig

/** Server and both in-app links must use the same endpoint for this build variant. */
object DebugDashboardEndpoint {
    val port: Int = BuildConfig.DEBUG_DASHBOARD_PORT
    val baseUrl: String = "http://127.0.0.1:$port/"
    val logsUrl: String = "${baseUrl}logs.zip"
}
