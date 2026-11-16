package com.damy.pantry

import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** True only when the current default network is up and validated. A lost callback must not treat a null network as online. */
fun connectivityOnline(manager: ConnectivityManager): Boolean {
    val network = manager.activeNetwork ?: return false
    val caps = manager.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
