package com.damy.pantry.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.damy.pantry.BuildConfig

object DebugInput {
    var target: ((String) -> Unit)? = null

    fun bind(block: (String) -> Unit) {
        target = block
    }
}

class DebugInputReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: return
        Handler(Looper.getMainLooper()).post { DebugInput.target?.invoke(text) }
    }
}

object LoginDebug {
    @Composable
    fun FillButton(onFill: (String, String) -> Unit) {
        if (BuildConfig.DEBUG_PHONE.isNotEmpty()) {
            TextButton(onClick = { onFill(BuildConfig.DEBUG_PHONE, BuildConfig.DEBUG_CODE) }) {
                Text("调试填充")
            }
        }
    }
}
