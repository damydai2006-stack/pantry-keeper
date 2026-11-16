package com.damy.pantry.capture

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object MateDiagnostics {
    @Composable
    fun Entry() {
        val scope = rememberCoroutineScope()
        var line by remember { mutableStateOf("未检测") }
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text("Mate 70 Pro 调试", fontSize = 16.sp)
            Text("${Build.MANUFACTURER} ${Build.MODEL}", fontSize = 16.sp)
            Text(line, fontSize = 16.sp)
            Button(onClick = {
                scope.launch {
                    line = runCatching { probe() }.getOrElse { "ML Kit 中文识别失败：${it.javaClass.simpleName}" }
                }
            }) { Text("检测 ML Kit") }
        }
    }
}

private suspend fun probe(): String = suspendCancellableCoroutine { cont ->
    val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
    val image = InputImage.fromBitmap(bitmap, 0)
    val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    recognizer.process(image).addOnSuccessListener { cont.resume("ML Kit 中文识别可用") }.addOnFailureListener {
        cont.resume("ML Kit 中文识别失败：${it.javaClass.simpleName}")
    }
}
