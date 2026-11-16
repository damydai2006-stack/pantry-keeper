package com.damy.pantry

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import com.damy.pantry.data.SessionCrypto
import com.damy.pantry.data.PrefSession
import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class QuinnFixesTest {
    @Test
    fun lostNetworkIsOfflineAndAValidatedNetworkIsOnline() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val shadow = shadowOf(manager)
        assertFalse(connectivityOnline(manager))

        shadow.setDefaultNetworkActive(true)
        val network = ShadowNetwork.newInstance(7)
        val info = ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true)
        shadow.setActiveNetworkInfo(info)
        shadow.addNetwork(network, info)
        val active = manager.activeNetwork
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadow.setNetworkCapabilities(active, caps)
        assertTrue(connectivityOnline(manager))

        shadow.clearAllNetworks()
        assertFalse(connectivityOnline(manager))
    }

    @Test
    fun sessionTokenIsNotStoredInPlaintext() {
        val context = RuntimeEnvironment.getApplication()
        val crypto = SessionCrypto(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
        val session = PrefSession(context, crypto)
        session.token = "plain-token"
        val raw = context.getSharedPreferences("pantry", Context.MODE_PRIVATE).getString("token", "")
        assertFalse(raw!!.contains("plain-token"))
        assertEquals("plain-token", session.token)
    }
}
