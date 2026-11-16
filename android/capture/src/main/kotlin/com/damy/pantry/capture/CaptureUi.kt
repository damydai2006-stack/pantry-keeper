package com.damy.pantry.capture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.damy.pantry.data.BarcodeHit
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume

enum class CaptureMode { SCAN, PHOTO }

data class CapturePrefill(
    val name: String = "",
    val category: String? = null,
    val expiry: String? = null,
    val production: String? = null,
    val shelfValue: Int? = null,
    val shelfUnit: String? = null,
    val note: String? = null,
)

private val brand = Color(0xFF1E4A62)
private val bg = Color(0xFFF4F1EB)
private val yellowSoft = Color(0xFFFBF3D0)
private val yellowText = Color(0xFF6B4E00)

@Composable
fun CaptureEntry(
    mode: CaptureMode,
    lookup: suspend (String) -> BarcodeHit,
    onDone: (CapturePrefill) -> Unit,
    onManual: (CapturePrefill) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var stage by remember { mutableStateOf("check") }
    var view by remember { mutableStateOf<OcrView?>(null) }
    var raw by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        stage = if (granted) "camera" else "denied"
    }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        stage = if (granted) "camera" else "explain"
    }
    when (stage) {
        "explain" -> CameraExplain(
            onCancel = onBack,
            onAllow = { launcher.launch(Manifest.permission.CAMERA) },
        )
        "denied" -> CameraDenied(
            onSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            },
            onManual = { onManual(CapturePrefill()) },
        )
        "camera" -> if (mode == CaptureMode.SCAN) {
            BarcodeCamera(
                onBack = onBack,
                onCode = { code ->
                    if (code.length == 20 && code.all { it.isDigit() }) {
                        message = "药品追溯码暂不能查名，请手填"
                        onManual(CapturePrefill(note = message))
                    } else {
                        stage = "lookup:$code"
                    }
                },
            )
        } else {
            PhotoCamera(onBack = onBack) { text ->
                raw = text
                val interpreted = OcrPipeline.interpret(text, com.damy.pantry.data.shanghaiToday())
                view = interpreted
                stage = if (interpreted is OcrView.Confirm) "confirm" else "fail"
            }
        }
        "confirm" -> {
            val confirm = view as? OcrView.Confirm
            if (confirm == null) FailScreen(raw, onRetake = { stage = "camera" }, onManual = { onManual(CapturePrefill()) }, onDate = { onManual(CapturePrefill()) })
            else ConfirmScreen(confirm, onBack = { stage = "camera" }, onManual = { onManual(prefillOf(confirm)) }, onNext = { chosen ->
                onDone(prefillOf(confirm).copy(expiry = chosen.toString()))
            })
        }
        "fail" -> FailScreen(raw, onRetake = { stage = "camera" }, onManual = { onManual(CapturePrefill()) }, onDate = { onManual(CapturePrefill()) })
        else -> if (stage.startsWith("lookup:")) {
            val code = stage.removePrefix("lookup:")
            LaunchedEffect(code) {
                val hit = runCatching { lookup(code) }.getOrElse { BarcodeHit(found = false, reason = "lookup_failed") }
                val foundName = hit.name
                if (hit.found && !foundName.isNullOrBlank()) {
                    onDone(CapturePrefill(name = foundName, category = hit.defaultCategory))
                } else {
                    val note = if (hit.reason == "trace_code") "药品追溯码暂不能查名，请手填" else "未查到名称，请手填"
                    onManual(CapturePrefill(note = note))
                }
            }
            Column(Modifier.fillMaxSize().background(bg).padding(20.dp)) {
                Text("正在查询条码", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun CameraExplain(onCancel: () -> Unit, onAllow: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("需要使用相机") },
        text = { Text("需要使用相机来扫描条形码和拍摄包装上的日期，照片只用于识别和保存到你的家庭清单。") },
        confirmButton = { TextButton(onClick = onAllow, modifier = Modifier.testTag("camera_allow")) { Text("允许") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消") } },
    )
}

@Composable
fun CameraDenied(onSettings: () -> Unit, onManual: () -> Unit) {
    Column(Modifier.fillMaxSize().background(bg).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("无法使用相机", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("没有相机权限也可以手动填写", fontSize = 16.sp)
        Button(onClick = onSettings, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("camera_settings"), colors = ButtonDefaults.buttonColors(containerColor = brand)) { Text("去设置") }
        OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("camera_manual")) { Text("手动填写") }
    }
}

@Composable
fun ConfirmScreen(view: OcrView.Confirm, onBack: () -> Unit, onManual: () -> Unit, onNext: (kotlinx.datetime.LocalDate) -> Unit) {
    var chosen by remember(view.expiry) { mutableStateOf(view.expiry) }
    Column(Modifier.fillMaxSize().background(bg).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("确认识别结果", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("请核对下面的信息，确认后才会入库。", fontSize = 16.sp)
        if (view.yellow) {
            Column(Modifier.fillMaxWidth().background(yellowSoft, RoundedCornerShape(12.dp)).padding(12.dp)) {
                Text("日期待确认", color = yellowText, fontWeight = FontWeight.Bold)
                view.notes.forEach { Text(it, color = yellowText, fontSize = 16.sp) }
                view.alternate?.let { alt ->
                    Text("另一种解释：$alt", color = yellowText, fontSize = 16.sp)
                    TextButton(onClick = { chosen = if (chosen == view.expiry) alt else view.expiry }) { Text("改用另一种解释") }
                }
            }
        }
        Text("到期日 $chosen", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("confirm_expiry"))
        view.production?.let { Text("生产日期 $it", fontSize = 16.sp) }
        view.shelf?.let { Text("保质期 ${it.raw}", fontSize = 16.sp) }
        Text(basisLine(view.basis), fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        Button(onClick = { onNext(chosen) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("confirm_next"), colors = ButtonDefaults.buttonColors(containerColor = brand)) {
            Text("确认，下一步")
        }
        OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("识别不准，改用手选") }
        TextButton(onClick = onBack) { Text("返回") }
    }
}

@Composable
fun FailScreen(raw: String, onRetake: () -> Unit, onManual: () -> Unit, onDate: () -> Unit) {
    Column(Modifier.fillMaxSize().background(bg).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("未能识别日期", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("没有识别出生产日期、保质期或到期日。可以换个角度重拍，也可以自己选择保质期长度或到期日。", fontSize = 16.sp)
        if (raw.isNotBlank()) Text(raw, fontSize = 16.sp, color = Color(0xFF5A615B))
        Button(onClick = onRetake, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("fail_retake"), colors = ButtonDefaults.buttonColors(containerColor = brand)) { Text("重拍") }
        OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("fail_manual")) { Text("手选保质期") }
        OutlinedButton(onClick = onDate, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("直接选到期日") }
    }
}

@Composable
private fun BarcodeCamera(onBack: () -> Unit, onCode: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp)) { TextButton(onClick = onBack) { Text("返回") }; Text("扫条形码", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        Text("对准条码。对准日期区域的拍照在另一入口。", modifier = Modifier.padding(horizontal = 20.dp), fontSize = 16.sp)
        AndroidView(factory = { PreviewView(it) }, modifier = Modifier.fillMaxSize()) { preview ->
            val cameraProvider = ProcessCameraProvider.getInstance(context)
            cameraProvider.addListener({
                val provider = cameraProvider.get()
                val previewUse = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
                val scanner = BarcodeScanning.getClient()
                val analysis = ImageAnalysis.Builder().build()
                val executor = Executors.newSingleThreadExecutor()
                analysis.setAnalyzer(executor) { image ->
                    if (busy) {
                        image.close()
                        return@setAnalyzer
                    }
                    val media = image.image
                    if (media == null) {
                        image.close()
                        return@setAnalyzer
                    }
                    val input = InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees)
                    scanner.process(input).addOnSuccessListener { codes ->
                        val value = codes.firstOrNull { it.format == Barcode.FORMAT_EAN_13 || it.format == Barcode.FORMAT_EAN_8 || it.format == Barcode.FORMAT_CODE_128 || it.format == Barcode.FORMAT_DATA_MATRIX }?.rawValue
                            ?: codes.firstOrNull()?.rawValue
                        if (!value.isNullOrBlank()) {
                            busy = true
                            scope.launch { onCode(value) }
                        }
                    }.addOnCompleteListener { image.close() }
                }
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, previewUse, analysis)
            }, ContextCompat.getMainExecutor(context))
        }
    }
}

@Composable
private fun PhotoCamera(onBack: () -> Unit, onText: (String) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var status by remember { mutableStateOf("对准日期区域") }
    Column(Modifier.fillMaxSize().background(bg)) {
        Row(Modifier.fillMaxWidth().padding(8.dp)) { TextButton(onClick = onBack) { Text("返回") }; Text("拍包装照片", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        Text(status, modifier = Modifier.padding(horizontal = 20.dp), fontSize = 16.sp)
        AndroidView(factory = { PreviewView(it) }, modifier = Modifier.weight(1f).fillMaxWidth()) { preview ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                val provider = future.get()
                val previewUse = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
                val imageCapture = ImageCapture.Builder().build()
                capture = imageCapture
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, previewUse, imageCapture)
            }, ContextCompat.getMainExecutor(context))
        }
        Button(
            onClick = {
                val imageCapture = capture ?: return@Button
                status = "正在识别"
                val executor = ContextCompat.getMainExecutor(context)
                imageCapture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        scope.launch {
                            val text = withTimeoutOrNull(5_000) { recognize(image) }.orEmpty()
                            image.close()
                            onText(text)
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        status = "拍摄失败，请重拍或手填"
                        onText("")
                    }
                })
            },
            modifier = Modifier.fillMaxWidth().padding(20.dp).heightIn(min = 48.dp).testTag("shutter"),
            colors = ButtonDefaults.buttonColors(containerColor = brand),
        ) { Text("拍摄并识别") }
    }
}

private suspend fun recognize(image: ImageProxy): String = suspendCancellableCoroutine { cont ->
    val media = image.image
    if (media == null) {
        cont.resume("")
        return@suspendCancellableCoroutine
    }
    val input = InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees)
    val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    recognizer.process(input).addOnSuccessListener { cont.resume(it.text) }.addOnFailureListener { cont.resume("") }
}

private fun prefillOf(view: OcrView.Confirm) = CapturePrefill(
    expiry = view.expiry.toString(),
    production = view.production?.toString(),
    shelfValue = view.shelf?.value,
    shelfUnit = view.shelf?.unit?.name?.lowercase(),
    note = view.notes.joinToString(" "),
)

private fun basisLine(basis: String?): String = when (basis) {
    "ocr_prod_plus_life" -> "从生产日期起算。"
    "ocr_expiry" -> "按包装上的到期日。"
    else -> "请核对到期日。"
}
