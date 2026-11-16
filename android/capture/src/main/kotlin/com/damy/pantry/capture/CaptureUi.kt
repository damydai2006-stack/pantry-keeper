package com.damy.pantry.capture

import android.Manifest
import androidx.activity.compose.BackHandler
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.damy.pantry.data.BarcodeHit
import com.pantrykeeper.core.date.DateMath
import com.pantrykeeper.core.date.ShelfUnit
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
    val manualMode: String? = null,
    val captureId: String? = null,
)

private val brand = Color(0xFF1E4A62)
private val bg = Color(0xFFF4F1EB)
private val yellowSoft = Color(0xFFFBF3D0)
private val yellowText = Color(0xFF6B4E00)

@Composable
fun CaptureEntry(
    mode: CaptureMode,
    captureId: String,
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
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, stage) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && stage == "denied") {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                if (granted) stage = "camera"
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    fun leave() {
        com.damy.pantry.data.PendingPhoto.abandon(captureId)
        onBack()
    }
    fun forward(draft: CapturePrefill) = onManual(draft.copy(captureId = captureId))
    fun finish(draft: CapturePrefill) = onDone(draft.copy(captureId = captureId))
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        stage = if (granted) "camera" else "explain"
    }
    when (stage) {
        "explain" -> CameraExplain(
            onCancel = { leave() },
            onAllow = { launcher.launch(Manifest.permission.CAMERA) },
        )
        "denied" -> CameraDenied(
            onSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            },
            onManual = { forward(CapturePrefill()) },
            onBack = { leave() },
        )
        "camera" -> if (mode == CaptureMode.SCAN) {
            BarcodeCamera(
                onBack = { leave() },
                onCode = { code ->
                    if (code.length == 20 && code.all { it.isDigit() }) {
                        message = "药品追溯码暂不能查名，请手填"
                        forward(CapturePrefill(note = message))
                    } else {
                        stage = "lookup:$code"
                    }
                },
            )
        } else {
            PhotoCamera(onBack = { leave() }) { text, jpeg ->
                if (jpeg != null) com.damy.pantry.data.PendingPhoto.storeCapture(captureId, jpeg)
                raw = text
                val interpreted = OcrPipeline.interpret(text, com.damy.pantry.data.shanghaiToday())
                view = interpreted
                stage = if (interpreted is OcrView.Confirm) "confirm" else "fail"
            }
        }
        "confirm" -> {
            val confirm = view as? OcrView.Confirm
            if (confirm == null) {
                FailScreen(
                    raw,
                    captureId = captureId,
                    onRetake = { stage = "camera" },
                    onManual = { forward(CapturePrefill(manualMode = "shelf")) },
                    onDate = { forward(CapturePrefill(manualMode = "date")) },
                    onBack = { leave() },
                )
            } else {
                val photo = com.damy.pantry.data.PendingPhoto.bytesFor(captureId)?.let { bytes ->
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }
                ConfirmScreen(
                    confirm,
                    photo,
                    captureId = captureId,
                    onBack = { stage = "camera" },
                    onManual = { draft -> forward(draft.copy(manualMode = "shelf")) },
                    onNext = { finish(it) },
                )
            }
        }
        "fail" -> FailScreen(
            raw,
            captureId = captureId,
            onRetake = { stage = "camera" },
            onManual = { forward(CapturePrefill(manualMode = "shelf")) },
            onDate = { forward(CapturePrefill(manualMode = "date")) },
            onBack = { leave() },
        )
        else -> if (stage.startsWith("lookup:")) {
            val code = stage.removePrefix("lookup:")
            BackHandler { leave() }
            LaunchedEffect(code) {
                val hit = runCatching { lookup(code) }.getOrElse { BarcodeHit(found = false, reason = "lookup_failed") }
                val foundName = hit.name
                if (hit.found && !foundName.isNullOrBlank()) {
                    finish(CapturePrefill(name = foundName, category = hit.defaultCategory))
                } else {
                    val note = if (hit.reason == "trace_code") "药品追溯码暂不能查名，请手填" else "未查到名称，请手填"
                    forward(CapturePrefill(note = note))
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
    BackHandler(onBack = onCancel)
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("需要使用相机") },
        text = {
            Column {
                Text(keepPhrases("需要使用相机来扫描"))
                Text(keepPhrases("条形码和拍摄包装上的日期，"))
                Text(keepPhrases("照片只用于识别和保存到你的家庭清单。"))
            }
        },
        confirmButton = { TextButton(onClick = onAllow, modifier = Modifier.testTag("camera_allow")) { Text("允许") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("取消") } },
    )
}

@Composable
fun CameraDenied(onSettings: () -> Unit, onManual: () -> Unit, onBack: () -> Unit = onManual) {
    BackHandler(onBack = onBack)
    AlertDialog(
        onDismissRequest = onBack,
        title = { Text("无法使用相机") },
        text = { Text(keepPhrases("没有相机权限也可以手动填写")) },
        confirmButton = {
            Button(onClick = onManual, modifier = Modifier.heightIn(min = 48.dp).testTag("camera_manual"), colors = ButtonDefaults.buttonColors(containerColor = brand)) { Text("手动填写") }
        },
        dismissButton = {
            TextButton(onClick = onSettings, modifier = Modifier.heightIn(min = 48.dp).testTag("camera_settings")) { Text("去设置") }
        },
    )
}

@Composable
fun ConfirmScreen(
    view: OcrView.Confirm,
    photo: androidx.compose.ui.graphics.ImageBitmap?,
    onBack: () -> Unit,
    onManual: (CapturePrefill) -> Unit,
    onNext: (CapturePrefill) -> Unit,
    captureId: String = "",
) {
    var name by remember { mutableStateOf("") }
    var production by remember { mutableStateOf(view.production?.toString().orEmpty()) }
    var shelfValue by remember { mutableStateOf(view.shelf?.value?.toString().orEmpty()) }
    var shelfUnit by remember { mutableStateOf(view.shelf?.unit) }
    var expiry by remember { mutableStateOf(view.expiry.toString()) }
    var chosen by remember { mutableStateOf(view.expiry) }
    val alternate = view.alternate
    fun recompute(fromProduction: String, valueText: String, unit: ShelfUnit?) {
        val chosenUnit = unit ?: return
        val start = runCatching { kotlinx.datetime.LocalDate.parse(fromProduction) }.getOrNull() ?: return
        val value = valueText.toIntOrNull() ?: return
        if (!DateMath.shelfAllowed(value, chosenUnit)) return
        val next = DateMath.addShelf(start, value, chosenUnit)
        expiry = next.toString()
        chosen = next
    }
    fun leave() {
        com.damy.pantry.data.PendingPhoto.abandon(captureId)
        onBack()
    }
    BackHandler { leave() }
    Column(Modifier.fillMaxSize().background(bg)) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("确认识别结果", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(keepPhrases("请核对下面的信息，确认后才会入库。"), fontSize = 16.sp)
        if (photo != null) {
            Image(photo, contentDescription = "包装照片", modifier = Modifier.fillMaxWidth().height(if (alternate != null) 96.dp else 140.dp).testTag("confirm_photo"), contentScale = ContentScale.Crop)
        }
        OutlinedTextField(name, { name = it.take(40) }, modifier = Modifier.fillMaxWidth().testTag("confirm_name"), label = { Text("名称") }, singleLine = true)
        OutlinedTextField(
            production,
            {
                production = it
                recompute(it, shelfValue, shelfUnit)
            },
            modifier = Modifier.fillMaxWidth().testTag("confirm_production"),
            label = { Text("生产日期") },
            singleLine = true,
        )
        Text("保质期", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(ShelfUnit.DAY to "天", ShelfUnit.MONTH to "月", ShelfUnit.YEAR to "年").forEach { (unit, label) ->
                FilterChip(selected = shelfUnit == unit, onClick = {
                    shelfUnit = unit
                    recompute(production, shelfValue, unit)
                }, label = { Text(label) })
            }
        }
        OutlinedTextField(
            shelfValue,
            {
                shelfValue = it.filter(Char::isDigit).take(4)
                recompute(production, it.filter(Char::isDigit).take(4), shelfUnit)
            },
            modifier = Modifier.fillMaxWidth().testTag("confirm_shelf"),
            label = { Text("保质期数字") },
            singleLine = true,
        )
        if (view.yellow && alternate != null) {
            Text("日期待确认", color = yellowText, fontWeight = FontWeight.Bold)
            view.notes.forEach { Text(keepPhrases(it), color = yellowText, fontSize = 16.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(view.expiry to "月/日", alternate to "日/月").forEach { (date, label) ->
                    val selected = chosen == date
                    Column(
                        Modifier.weight(1f).border(if (selected) 2.dp else 1.dp, if (selected) brand else yellowText, RoundedCornerShape(12.dp)).background(yellowSoft, RoundedCornerShape(12.dp)).padding(12.dp).testTag("reading_$date"),
                    ) {
                        Text(label, color = yellowText, fontSize = 16.sp)
                        TextButton(onClick = { chosen = date; expiry = date.toString() }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(date.toString(), color = yellowText, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        OutlinedTextField(
            expiry,
            { expiry = it; chosen = runCatching { kotlinx.datetime.LocalDate.parse(it) }.getOrNull() ?: chosen },
            modifier = Modifier.fillMaxWidth().testTag("confirm_expiry").then(if (view.yellow) Modifier.border(1.dp, yellowText, RoundedCornerShape(4.dp)) else Modifier),
            label = { Text("到期日") },
            singleLine = true,
        )
        Text(basisLine(view.basis), fontSize = 16.sp)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                onNext(
                    CapturePrefill(
                        name = name,
                        expiry = expiry,
                        production = production.ifBlank { null },
                        shelfValue = if (shelfUnit == null) null else shelfValue.toIntOrNull(),
                        shelfUnit = shelfUnit?.name?.lowercase(),
                        note = view.notes.joinToString(" "),
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("confirm_next"),
            colors = ButtonDefaults.buttonColors(containerColor = brand),
        ) { Text("确认，下一步") }
        OutlinedButton(onClick = {
            onManual(CapturePrefill(name = name, expiry = expiry, production = production.ifBlank { null }, manualMode = "shelf"))
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("识别不准，改用手选") }
        TextButton(onClick = { leave() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("返回") }
    }
    }
}

@Composable
fun FailScreen(
    raw: String,
    onRetake: () -> Unit,
    onManual: () -> Unit,
    onDate: () -> Unit,
    captureId: String = "",
    onBack: () -> Unit = {},
) {
    BackHandler {
        com.damy.pantry.data.PendingPhoto.abandon(captureId)
        onBack()
    }
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
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp)) { TextButton(onClick = onBack) { Text("返回") }; Text("扫条形码", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        Text("对准条码。对准日期区域的拍照在另一入口。", modifier = Modifier.padding(horizontal = 20.dp), fontSize = 16.sp)
        AndroidView(factory = { PreviewView(it).also { view -> previewView = view } }, modifier = Modifier.fillMaxSize())
    }
    DisposableEffect(previewView) {
        val view = previewView ?: return@DisposableEffect onDispose {}
        val executor = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(context)
        val scanner = BarcodeScanning.getClient()
        future.addListener({
            val provider = future.get()
            val previewUse = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
            val analysis = ImageAnalysis.Builder().build()
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
                    val value = codes.firstOrNull {
                        it.format == Barcode.FORMAT_EAN_13 || it.format == Barcode.FORMAT_EAN_8 || it.format == Barcode.FORMAT_CODE_128 || it.format == Barcode.FORMAT_DATA_MATRIX
                    }?.rawValue
                    if (!value.isNullOrBlank() && value.all { it.isDigit() } && value.length in setOf(8, 12, 13, 14, 20)) {
                        busy = true
                        scope.launch { onCode(value) }
                    }
                }.addOnCompleteListener { image.close() }
            }
            provider.unbindAll()
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, previewUse, analysis)
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            executor.shutdown()
            runCatching { if (future.isDone) future.get().unbindAll() }
            scanner.close()
        }
    }
}

@Composable
private fun PhotoCamera(onBack: () -> Unit, onText: (String, ByteArray?) -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var status by remember { mutableStateOf("对准日期区域") }
    Column(Modifier.fillMaxSize().background(bg)) {
        Row(Modifier.fillMaxWidth().padding(8.dp)) { TextButton(onClick = onBack) { Text("返回") }; Text("拍包装照片", fontSize = 22.sp, fontWeight = FontWeight.Bold) }
        Text(status, modifier = Modifier.padding(horizontal = 20.dp), fontSize = 16.sp)
        AndroidView(factory = { PreviewView(it).also { view -> previewView = view } }, modifier = Modifier.weight(1f).fillMaxWidth())
        Button(
            onClick = {
                val imageCapture = capture ?: return@Button
                status = "正在识别"
                val executor = ContextCompat.getMainExecutor(context)
                imageCapture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val jpeg = jpegBytes(image)
                        scope.launch {
                            val text = withTimeoutOrNull(5_000) { recognize(image) }.orEmpty()
                            image.close()
                            onText(text, jpeg)
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        status = "拍摄失败，请重拍或手填"
                        onText("", null)
                    }
                })
            },
            modifier = Modifier.fillMaxWidth().padding(20.dp).heightIn(min = 48.dp).testTag("shutter"),
            colors = ButtonDefaults.buttonColors(containerColor = brand),
        ) { Text("拍摄并识别") }
    }
    DisposableEffect(previewView) {
        val view = previewView ?: return@DisposableEffect onDispose {}
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val previewUse = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
            val imageCapture = ImageCapture.Builder().build()
            capture = imageCapture
            provider.unbindAll()
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, previewUse, imageCapture)
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            capture = null
            runCatching { if (future.isDone) future.get().unbindAll() }
        }
    }
}

private suspend fun recognize(image: ImageProxy): String {
    val media = image.image ?: return ""
    val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    return try {
        suspendCancellableCoroutine { cont ->
            val input = InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees)
            recognizer.process(input)
                .addOnSuccessListener { if (cont.isActive) cont.resume(it.text) }
                .addOnFailureListener { if (cont.isActive) cont.resume("") }
        }
    } finally {
        recognizer.close()
    }
}

private fun prefillOf(view: OcrView.Confirm) = CapturePrefill(
    expiry = view.expiry.toString(),
    production = view.production?.toString(),
    shelfValue = view.shelf?.value,
    shelfUnit = view.shelf?.unit?.name?.lowercase(),
    note = view.notes.joinToString(" "),
)

fun compressForUpload(bytes: ByteArray, maxBytes: Int = 8 * 1024 * 1024): ByteArray {
    val decoded = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
    val upright = applyExifOrientation(decoded, bytes)
    val maxEdge = 1600
    val scale = minOf(1f, maxEdge.toFloat() / maxOf(upright.width, upright.height))
    val width = maxOf(1, (upright.width * scale).toInt())
    val height = maxOf(1, (upright.height * scale).toInt())
    val scaled = if (width == upright.width && height == upright.height) upright else android.graphics.Bitmap.createScaledBitmap(upright, width, height, true)
    var quality = 80
    var out = ByteArray(0)
    while (quality >= 40) {
        val stream = java.io.ByteArrayOutputStream()
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, stream)
        out = stream.toByteArray()
        if (out.size <= maxBytes) return out
        quality -= 10
    }
    return out
}

private fun applyExifOrientation(bitmap: android.graphics.Bitmap, jpeg: ByteArray): android.graphics.Bitmap {
    val orientation = runCatching {
        androidx.exifinterface.media.ExifInterface(java.io.ByteArrayInputStream(jpeg)).getAttributeInt(
            androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
            androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL,
        )
    }.getOrDefault(androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)
    val matrix = android.graphics.Matrix()
    when (orientation) {
        androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
        androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
        androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSPOSE -> {
            matrix.postRotate(90f)
            matrix.preScale(-1f, 1f)
        }
        androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSVERSE -> {
            matrix.postRotate(270f)
            matrix.preScale(-1f, 1f)
        }
        else -> return bitmap
    }
    return android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

private fun jpegBytes(image: ImageProxy): ByteArray? {
    val buffer = image.planes.firstOrNull()?.buffer ?: return null
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return bytes.takeIf { it.size >= 3 && it[0] == 0xFF.toByte() && it[1] == 0xD8.toByte() }
}

private fun Char.isCjk(): Boolean = code in 0x4E00..0x9FFF

private fun keepPhrases(text: String): String = buildString {
    text.forEachIndexed { index, ch ->
        if (index > 0 && ch.isCjk() && text[index - 1].isCjk()) append('\u2060')
        append(ch)
    }
}

@Composable
fun ShelfPickScreen(today: kotlinx.datetime.LocalDate, onBack: () -> Unit, onDone: (CapturePrefill) -> Unit, captureId: String = "") {
    var custom by remember { mutableStateOf("30") }
    var unit by remember { mutableStateOf(ShelfUnit.DAY) }
    var useDate by remember { mutableStateOf(false) }
    var expiry by remember { mutableStateOf("") }
    var preset by remember { mutableStateOf<Pair<Int, ShelfUnit>?>(7 to ShelfUnit.DAY) }
    fun preview(value: Int, chosen: ShelfUnit): String = DateMath.addShelf(today, value, chosen).toString()
    val shown = when {
        useDate -> expiry
        preset != null -> preview(preset!!.first, preset!!.second)
        custom.toIntOrNull()?.let { DateMath.shelfAllowed(it, unit) } == true -> preview(custom.toInt(), unit)
        else -> ""
    }
    fun leave() {
        com.damy.pantry.data.PendingPhoto.abandon(captureId)
        onBack()
    }
    BackHandler { leave() }
    Column(Modifier.fillMaxSize().background(bg).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("手选保质期", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(keepPhrases("从今天起算。也可以改成直接选择到期日。"), fontSize = 16.sp)
        listOf(7 to ShelfUnit.DAY, 1 to ShelfUnit.MONTH, 6 to ShelfUnit.MONTH, 1 to ShelfUnit.YEAR, 2 to ShelfUnit.YEAR).forEach { (value, chosen) ->
            val label = when (chosen) {
                ShelfUnit.DAY -> "$value 天"
                ShelfUnit.MONTH -> "$value 个月"
                ShelfUnit.YEAR -> "$value 年"
            }
            FilterChip(selected = !useDate && preset == value to chosen, onClick = { useDate = false; preset = value to chosen }, label = { Text(label) }, modifier = Modifier.testTag("pick_$label"))
        }
        Text("自定义", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(custom, { custom = it.filter(Char::isDigit).take(4); preset = null; useDate = false }, modifier = Modifier.weight(1f).testTag("custom_shelf"), singleLine = true)
            listOf(ShelfUnit.DAY to "天", ShelfUnit.MONTH to "月", ShelfUnit.YEAR to "年").forEach { (chosen, label) ->
                FilterChip(selected = !useDate && preset == null && unit == chosen, onClick = { unit = chosen; preset = null; useDate = false }, label = { Text(label) })
            }
        }
        TextButton(onClick = { useDate = !useDate }, modifier = Modifier.testTag("pick_date_mode")) {
            Text(if (useDate) "改为按保质期" else "直接选择到期日")
        }
        if (useDate) {
            OutlinedTextField(expiry, { expiry = it }, modifier = Modifier.fillMaxWidth().testTag("pick_expiry"), label = { Text("到期日") }, singleLine = true)
        }
        Text(if (shown.isBlank()) "请选择保质期" else "到期日 $shown", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("pick_preview"))
        Button(onClick = {
            val value = preset?.first ?: custom.toIntOrNull()
            val chosenUnit = preset?.second ?: unit
            onDone(
                CapturePrefill(
                    expiry = if (useDate) expiry else shown,
                    shelfValue = if (useDate) null else value,
                    shelfUnit = if (useDate) null else chosenUnit.name.lowercase(),
                    manualMode = if (useDate) "date" else "shelf",
                    captureId = captureId,
                ),
            )
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("pick_next"), colors = ButtonDefaults.buttonColors(containerColor = brand)) {
            Text("下一步")
        }
        TextButton(onClick = { leave() }, modifier = Modifier.testTag("shelf_back")) { Text("返回") }
    }
}

private fun basisLine(basis: String?): String = when (basis) {
    "ocr_prod_plus_life" -> "从生产日期起算。"
    "ocr_expiry" -> "按包装上的到期日。"
    else -> "请核对到期日。"
}
