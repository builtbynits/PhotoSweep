@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.photosweep

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// ─── Data Models ───────────────────────────────────────────

data class Photo(
    val id: Long,
    val uri: Uri,
    val name: String,
    val size: Long,
    val width: Int,
    val height: Int,
    val dHash: Long = 0L,
    val aHash: Long = 0L,
    val histogram: List<Float> = emptyList(),
    val ocrText: String? = null,
    val ocrDone: Boolean = false,
    val blurScore: Double = 0.0,
    val folderName: String = ""
)

data class DupGroup(val photos: List<Photo>)

// ─── Activity ──────────────────────────────────────────────

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                PhotoSweepApp()
            }
        }
    }
}

// ─── Theme ─────────────────────────────────────────────────

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF1565C0),
            onPrimary = Color.White,
            secondary = Color(0xFF42A5F5),
            surface = Color(0xFFF5F5F5),
            background = Color.White,
            error = Color(0xFFD32F2F)
        ),
        content = content
    )
}

// ─── Utility Functions ─────────────────────────────────────

fun computeDHash(bmp: Bitmap): Long {
    val scaled = Bitmap.createScaledBitmap(bmp, 9, 8, true)
    var hash = 0L
    for (y in 0 until 8) {
        for (x in 0 until 8) {
            val left = grayPixel(scaled, x, y)
            val right = grayPixel(scaled, x + 1, y)
            if (left > right) {
                hash = hash or (1L shl (y * 8 + x))
            }
        }
    }
    if (scaled != bmp) scaled.recycle()
    return hash
}

fun computeAHash(bmp: Bitmap): Long {
    val scaled = Bitmap.createScaledBitmap(bmp, 8, 8, true)
    var sum = 0.0
    val values = mutableListOf<Double>()
    for (y in 0 until 8) {
        for (x in 0 until 8) {
            val g = grayPixel(scaled, x, y).toDouble()
            values.add(g)
            sum += g
        }
    }
    val avg = sum / 64.0
    var hash = 0L
    for (i in values.indices) {
        if (values[i] >= avg) {
            hash = hash or (1L shl i)
        }
    }
    if (scaled != bmp) scaled.recycle()
    return hash
}

fun grayPixel(bmp: Bitmap, x: Int, y: Int): Int {
    val px = bmp.getPixel(x, y)
    val r = AndroidColor.red(px)
    val g = AndroidColor.green(px)
    val b = AndroidColor.blue(px)
    return (0.299 * r + 0.587 * g + 0.114 * b).toInt()
}

fun computeHistogram(bmp: Bitmap): List<Float> {
    val scaled = Bitmap.createScaledBitmap(bmp, 32, 32, true)
    val bins = FloatArray(64) // 4*4*4
    val total = 32 * 32
    for (y in 0 until 32) {
        for (x in 0 until 32) {
            val px = scaled.getPixel(x, y)
            val rBin = (AndroidColor.red(px) * 4) / 256
            val gBin = (AndroidColor.green(px) * 4) / 256
            val bBin = (AndroidColor.blue(px) * 4) / 256
            val idx = rBin * 16 + gBin * 4 + bBin
            bins[idx] += 1f
        }
    }
    if (scaled != bmp) scaled.recycle()
    return bins.map { it / total }.toList()
}

fun hammingDistance(a: Long, b: Long): Int {
    return java.lang.Long.bitCount(a xor b)
}

fun histogramDiff(a: List<Float>, b: List<Float>): Float {
    if (a.size != b.size || a.isEmpty()) return 1f
    var sum = 0f
    for (i in a.indices) {
        sum += abs(a[i] - b[i])
    }
    return sum / 2f
}

fun similarityScore(p1: Photo, p2: Photo): Float {
    val dDist = hammingDistance(p1.dHash, p2.dHash)
    val aDist = hammingDistance(p1.aHash, p2.aHash)
    val hDiff = histogramDiff(p1.histogram, p2.histogram)
    return dDist * 0.5f + aDist * 0.3f + hDiff * 20f
}

fun computeBlurScore(bmp: Bitmap): Double {
    val w = min(bmp.width, 64)
    val h = min(bmp.height, 64)
    val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
    val gray = Array(h) { y -> IntArray(w) { x -> grayPixel(scaled, x, y) } }
    if (scaled != bmp) scaled.recycle()

    var sum = 0.0
    var sum2 = 0.0
    var count = 0
    for (y in 1 until h - 1) {
        for (x in 1 until w - 1) {
            val lap = gray[y - 1][x] + gray[y + 1][x] + gray[y][x - 1] + gray[y][x + 1] - 4 * gray[y][x]
            sum += lap
            sum2 += lap.toDouble() * lap.toDouble()
            count++
        }
    }
    if (count == 0) return 0.0
    val mean = sum / count
    val variance = sum2 / count - mean * mean
    return variance
}

fun loadSmallBitmap(context: Context, uri: Uri, maxDim: Int = 128): Bitmap? {
    return try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        val w = opts.outWidth
        val h = opts.outHeight
        var sample = 1
        while (w / sample > maxDim && h / sample > maxDim) sample *= 2
        val opts2 = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts2) }
    } catch (e: Exception) {
        null
    }
}

fun getFolderName(data: String?): String {
    if (data.isNullOrBlank()) return ""
    val f = File(data)
    return f.parentFile?.name ?: ""
}

fun formatSize(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}

fun scanPhotos(context: Context): List<Photo> {
    val list = mutableListOf<Photo>()
    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.SIZE,
        MediaStore.Images.Media.WIDTH,
        MediaStore.Images.Media.HEIGHT,
        MediaStore.Images.Media.DATA
    )
    val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
    val cursor = context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        projection, null, null, sortOrder
    )
    cursor?.use { c ->
        val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
        val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
        val wCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
        val hCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
        val dataCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
        while (c.moveToNext()) {
            val id = c.getLong(idCol)
            val name = c.getString(nameCol) ?: "unknown"
            val size = c.getLong(sizeCol)
            val w = c.getInt(wCol)
            val h = c.getInt(hCol)
            val data = c.getString(dataCol)
            val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            val folder = getFolderName(data)
            list.add(Photo(id = id, uri = uri, name = name, size = size, width = w, height = h, folderName = folder))
        }
    }
    return list
}

// ─── Main Composable ───────────────────────────────────────

@Composable
fun PhotoSweepApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var hasPermission by remember { mutableStateOf(false) }
    var permDenied by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }

    val photos = remember { mutableStateListOf<Photo>() }
    var isScanning by remember { mutableStateOf(false) }
    var scanProgress by remember { mutableFloatStateOf(0f) }
    var scanTotal by remember { mutableIntStateOf(0) }
    var scanCurrent by remember { mutableIntStateOf(0) }

    val markedForDeletion = remember { mutableStateListOf<Long>() }

    val dupGroups = remember { mutableStateListOf<DupGroup>() }
    var dupThreshold by remember { mutableFloatStateOf(10f) }
    var isFindingDups by remember { mutableStateOf(false) }

    var isOcrRunning by remember { mutableStateOf(false) }
    var ocrProgress by remember { mutableFloatStateOf(0f) }
    var ocrTotal by remember { mutableIntStateOf(0) }
    var ocrCurrent by remember { mutableIntStateOf(0) }
    var ocrQuery by remember { mutableStateOf("") }

    var previewPhoto by remember { mutableStateOf<Photo?>(null) }

    val permToRequest = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        permDenied = !granted
    }

    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val toRemove = markedForDeletion.toList()
            photos.removeAll { it.id in toRemove }
            dupGroups.clear()
            markedForDeletion.clear()
            Toast.makeText(context, "Deleted successfully", Toast.LENGTH_SHORT).show()
        }
    }

    fun requestPerm() {
        permLauncher.launch(permToRequest)
    }

    fun doScan() {
        if (isScanning) return
        scope.launch {
            isScanning = true
            scanProgress = 0f
            val rawPhotos = withContext(Dispatchers.IO) { scanPhotos(context) }
            scanTotal = rawPhotos.size
            scanCurrent = 0
            photos.clear()
            dupGroups.clear()
            markedForDeletion.clear()

            val batchSize = 20
            for (i in rawPhotos.indices step batchSize) {
                val end = min(i + batchSize, rawPhotos.size)
                val batch = rawPhotos.subList(i, end)
                val processed = withContext(Dispatchers.Default) {
                    batch.map { photo ->
                        val bmp = loadSmallBitmap(context, photo.uri, 128)
                        if (bmp != null) {
                            val dh = computeDHash(bmp)
                            val ah = computeAHash(bmp)
                            val hist = computeHistogram(bmp)
                            val blur = computeBlurScore(bmp)
                            bmp.recycle()
                            photo.copy(dHash = dh, aHash = ah, histogram = hist, blurScore = blur)
                        } else {
                            photo
                        }
                    }
                }
                photos.addAll(processed)
                scanCurrent = min(end, rawPhotos.size)
                scanProgress = if (scanTotal > 0) scanCurrent.toFloat() / scanTotal else 1f
            }
            isScanning = false
            scanProgress = 1f
        }
    }

    fun findDuplicates() {
        if (isFindingDups) return
        scope.launch {
            isFindingDups = true
            dupGroups.clear()
            val groups = withContext(Dispatchers.Default) {
                val photoList = photos.toList()
                val used = mutableSetOf<Long>()
                val result = mutableListOf<DupGroup>()
                for (i in photoList.indices) {
                    if (photoList[i].id in used) continue
                    val group = mutableListOf(photoList[i])
                    for (j in i + 1 until photoList.size) {
                        if (photoList[j].id in used) continue
                        val score = similarityScore(photoList[i], photoList[j])
                        if (score < dupThreshold) {
                            group.add(photoList[j])
                            used.add(photoList[j].id)
                        }
                    }
                    if (group.size > 1) {
                        used.add(photoList[i].id)
                        result.add(DupGroup(group))
                    }
                }
                result
            }
            dupGroups.addAll(groups)
            isFindingDups = false
        }
    }

    fun autoMark() {
        markedForDeletion.clear()
        for (group in dupGroups) {
            val best = group.photos.maxByOrNull { it.width.toLong() * it.height.toLong() }
            for (p in group.photos) {
                if (p.id != best?.id) {
                    if (p.id !in markedForDeletion) markedForDeletion.add(p.id)
                }
            }
        }
    }

    fun deleteMarked() {
        if (markedForDeletion.isEmpty()) return
        val uris = markedForDeletion.mapNotNull { id ->
            photos.find { it.id == id }?.uri
        }
        if (uris.isEmpty()) return

        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, uris)
                val request = IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                deleteLauncher.launch(request)
            } catch (e: Exception) {
                Toast.makeText(context, "Delete error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            var count = 0
            for (uri in uris) {
                try {
                    val rows = context.contentResolver.delete(uri, null, null)
                    if (rows > 0) count++
                } catch (_: Exception) {}
            }
            val toRemove = markedForDeletion.toList()
            photos.removeAll { it.id in toRemove }
            dupGroups.clear()
            markedForDeletion.clear()
            Toast.makeText(context, "Deleted $count photos", Toast.LENGTH_SHORT).show()
        }
    }

    fun runOcr() {
        if (isOcrRunning) return
        scope.launch {
            isOcrRunning = true
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val pending = photos.filter { !it.ocrDone }.toList()
            ocrTotal = pending.size
            ocrCurrent = 0
            ocrProgress = 0f

            for (p in pending) {
                try {
                    val bmp = withContext(Dispatchers.IO) { loadSmallBitmap(context, p.uri, 512) }
                    if (bmp != null) {
                        val image = InputImage.fromBitmap(bmp, 0)
                        val result = recognizer.process(image).await()
                        val text = result.text.takeIf { it.isNotBlank() }
                        bmp.recycle()
                        val idx = photos.indexOfFirst { it.id == p.id }
                        if (idx >= 0) {
                            photos[idx] = photos[idx].copy(ocrText = text, ocrDone = true)
                        }
                    } else {
                        val idx = photos.indexOfFirst { it.id == p.id }
                        if (idx >= 0) {
                            photos[idx] = photos[idx].copy(ocrDone = true)
                        }
                    }
                } catch (_: Exception) {
                    val idx = photos.indexOfFirst { it.id == p.id }
                    if (idx >= 0) {
                        photos[idx] = photos[idx].copy(ocrDone = true)
                    }
                }
                ocrCurrent++
                ocrProgress = if (ocrTotal > 0) ocrCurrent.toFloat() / ocrTotal else 1f
            }
            isOcrRunning = false
            ocrProgress = 1f
        }
    }

    fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("OCR Text", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    fun exportOcrText() {
        scope.launch(Dispatchers.IO) {
            try {
                val sb = StringBuilder()
                sb.appendLine("PhotoSweep OCR Export")
                sb.appendLine("=".repeat(40))
                for (p in photos) {
                    if (!p.ocrText.isNullOrBlank()) {
                        sb.appendLine("\n--- ${p.name} ---")
                        sb.appendLine(p.ocrText)
                    }
                }
                val text = sb.toString()

                if (Build.VERSION.SDK_INT >= 29) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "PhotoSweep_OCR.txt")
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS)
                    }
                    val uri = context.contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
                    uri?.let {
                        context.contentResolver.openOutputStream(it)?.use { os ->
                            os.write(text.toByteArray())
                        }
                    }
                } else {
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                    dir.mkdirs()
                    val file = File(dir, "PhotoSweep_OCR.txt")
                    FileOutputStream(file).use { it.write(text.toByteArray()) }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Exported to Documents/PhotoSweep_OCR.txt", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ─── Permission Screen ─────────────────────────
    if (!hasPermission) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(80.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(24.dp))
                Text("PhotoSweep", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text("Find duplicates, read text, clean up your gallery", textAlign = TextAlign.Center, color = Color.Gray)
                Spacer(Modifier.height(32.dp))
                Button(onClick = { requestPerm() }, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) {
                    Text("Grant Photo Access", fontSize = 16.sp)
                }
                if (permDenied) {
                    Spacer(Modifier.height(16.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)), modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Permission denied. Please grant photo access in Settings to use PhotoSweep.",
                            modifier = Modifier.padding(16.dp),
                            color = Color(0xFFE65100)
                        )
                    }
                }
            }
        }
        return
    }

    // ─── Preview Dialog ────────────────────────────
    if (previewPhoto != null) {
        val pp = previewPhoto!!
        Dialog(onDismissRequest = { previewPhoto = null }) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(pp.uri).crossfade(true).build(),
                        contentDescription = pp.name,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Fit
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(pp.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${pp.width} × ${pp.height}  •  ${formatSize(pp.size)}", color = Color.Gray, fontSize = 12.sp)
                    Text("Blur score: ${"%.1f".format(pp.blurScore)}", color = if (pp.blurScore < 100) Color(0xFFE65100) else Color.Gray, fontSize = 12.sp)
                    Text("Folder: ${pp.folderName}", color = Color.Gray, fontSize = 12.sp)
                    if (!pp.ocrText.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text("OCR Text:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(pp.ocrText, fontSize = 12.sp, maxLines = 8, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(onClick = { copyToClipboard(pp.ocrText) }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Copy OCR Text", fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { previewPhoto = null }) { Text("Close") }
                        Spacer(Modifier.width(8.dp))
                        if (pp.id in markedForDeletion) {
                            Button(onClick = { markedForDeletion.remove(pp.id) }, colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)) {
                                Text("Unmark")
                            }
                        } else {
                            Button(onClick = { markedForDeletion.add(pp.id) }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Mark Delete")
                            }
                        }
                    }
                }
            }
        }
    }

    // ─── Main Scaffold ─────────────────────────────

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PhotoSweep", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = Color.White
                ),
                actions = {
                    if (markedForDeletion.isNotEmpty()) {
                        Button(
                            onClick = { deleteMarked() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                            modifier = Modifier.padding(end = 8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                            Spacer(Modifier.width(4.dp))
                            Text("${markedForDeletion.size}", color = Color.White, fontSize = 13.sp)
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                val tabs = listOf("Home" to Icons.Filled.Home, "Gallery" to Icons.Filled.Image, "Dupes" to Icons.Filled.PhotoLibrary, "OCR" to Icons.Filled.TextSnippet)
                tabs.forEachIndexed { index, (label, icon) ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label, fontSize = 11.sp) }
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (selectedTab) {
                0 -> HomeTab(
                    photos = photos,
                    isScanning = isScanning,
                    scanProgress = scanProgress,
                    scanCurrent = scanCurrent,
                    scanTotal = scanTotal,
                    dupGroupsCount = dupGroups.size,
                    ocrDoneCount = photos.count { it.ocrDone && !it.ocrText.isNullOrBlank() },
                    onScan = { doScan() },
                    onPhotoClick = { previewPhoto = it },
                    context = context
                )
                1 -> GalleryTab(
                    photos = photos,
                    markedForDeletion = markedForDeletion,
                    onPhotoClick = { previewPhoto = it },
                    onLongPress = { id ->
                        if (id in markedForDeletion) markedForDeletion.remove(id)
                        else markedForDeletion.add(id)
                    },
                    context = context
                )
                2 -> DuplicatesTab(
                    photos = photos,
                    dupGroups = dupGroups,
                    dupThreshold = dupThreshold,
                    onThresholdChange = { dupThreshold = it },
                    isFinding = isFindingDups,
                    markedForDeletion = markedForDeletion,
                    onFindDups = { findDuplicates() },
                    onAutoMark = { autoMark() },
                    onToggleMark = { id ->
                        if (id in markedForDeletion) markedForDeletion.remove(id)
                        else markedForDeletion.add(id)
                    },
                    onPhotoClick = { previewPhoto = it },
                    context = context
                )
                3 -> OcrTab(
                    photos = photos,
                    isOcrRunning = isOcrRunning,
                    ocrProgress = ocrProgress,
                    ocrCurrent = ocrCurrent,
                    ocrTotal = ocrTotal,
                    ocrQuery = ocrQuery,
                    onQueryChange = { ocrQuery = it },
                    onRunOcr = { runOcr() },
                    onExport = { exportOcrText() },
                    onPhotoClick = { previewPhoto = it },
                    onCopy = { copyToClipboard(it) },
                    context = context
                )
            }
        }
    }
}

// ─── HOME TAB ──────────────────────────────────────────────

@Composable
fun HomeTab(
    photos: List<Photo>,
    isScanning: Boolean,
    scanProgress: Float,
    scanCurrent: Int,
    scanTotal: Int,
    dupGroupsCount: Int,
    ocrDoneCount: Int,
    onScan: () -> Unit,
    onPhotoClick: (Photo) -> Unit,
    context: Context
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Welcome to PhotoSweep", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text("Scan, deduplicate, and search text in your photos", color = Color.Gray, fontSize = 14.sp)
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("Photos", "${photos.size}", Icons.Filled.Image, Color(0xFF1565C0), Modifier.weight(1f))
                StatCard("OCR Done", "$ocrDoneCount", Icons.Filled.TextSnippet, Color(0xFF2E7D32), Modifier.weight(1f))
                StatCard("Dup Groups", "$dupGroupsCount", Icons.Filled.PhotoLibrary, Color(0xFFE65100), Modifier.weight(1f))
            }
        }

        item {
            val ssCount = photos.count { it.folderName.equals("Screenshots", ignoreCase = true) }
            val blurryCount = photos.count { it.blurScore < 100 && it.blurScore > 0 }
            val largePhotos = photos.sortedByDescending { it.size }.take(50)
            val totalLargeSize = largePhotos.sumOf { it.size }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("Screenshots", "$ssCount", Icons.Filled.Image, Color(0xFF7B1FA2), Modifier.weight(1f))
                StatCard("Blurry", "$blurryCount", Icons.Filled.Image, Color(0xFFC62828), Modifier.weight(1f))
                StatCard("Top50 Size", formatSize(totalLargeSize), Icons.Filled.Image, Color(0xFF00695C), Modifier.weight(1f))
            }
        }

        item {
            Button(
                onClick = onScan,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(16.dp),
                enabled = !isScanning
            ) {
                if (isScanning) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Scanning $scanCurrent / $scanTotal...")
                } else {
                    Icon(Icons.Filled.Search, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Scan My Photos", fontSize = 16.sp)
                }
            }
        }

        if (isScanning) {
            item {
                LinearProgressIndicator(progress = { scanProgress }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)))
            }
        }

        if (photos.isNotEmpty()) {
            item {
                Text("Recent Photos", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }
            item {
                val recentPhotos = photos.take(30)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxWidth().height(400.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(recentPhotos, key = { it.id }) { photo ->
                        AsyncImage(
                            model = ImageRequest.Builder(context).data(photo.uri).crossfade(true).size(200).build(),
                            contentDescription = photo.name,
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .combinedClickable(
                                    onClick = { onPhotoClick(photo) }
                                ),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StatCard(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(4.dp))
            Text(value, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = color)
            Text(label, fontSize = 11.sp, color = color.copy(alpha = 0.7f))
        }
    }
}

// ─── GALLERY TAB ───────────────────────────────────────────

@Composable
fun GalleryTab(
    photos: List<Photo>,
    markedForDeletion: List<Long>,
    onPhotoClick: (Photo) -> Unit,
    onLongPress: (Long) -> Unit,
    context: Context
) {
    var tabMode by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf("All", "Screenshots", "Blurry", "Large").forEachIndexed { idx, label ->
                OutlinedButton(
                    onClick = { tabMode = idx },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                    colors = if (tabMode == idx) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    else ButtonDefaults.outlinedButtonColors()
                ) {
                    Text(
                        label,
                        fontSize = 11.sp,
                        maxLines = 1,
                        color = if (tabMode == idx) Color.White else MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        val filteredPhotos = when (tabMode) {
            1 -> photos.filter { it.folderName.equals("Screenshots", ignoreCase = true) }
            2 -> photos.filter { it.blurScore in 0.01..100.0 }
            3 -> photos.sortedByDescending { it.size }.take(50)
            else -> photos
        }

        if (tabMode == 3 && filteredPhotos.isNotEmpty()) {
            val totalSize = filteredPhotos.sumOf { it.size }
            Text(
                "Top ${filteredPhotos.size} largest: ${formatSize(totalSize)} reclaimable",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                fontSize = 12.sp,
                color = Color(0xFFE65100),
                fontWeight = FontWeight.SemiBold
            )
        }

        if (filteredPhotos.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No photos found. Scan first!", color = Color.Gray)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(100.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(filteredPhotos, key = { it.id }) { photo ->
                    val isMarked = photo.id in markedForDeletion
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .then(if (isMarked) Modifier.border(3.dp, Color.Red, RoundedCornerShape(8.dp)) else Modifier)
                            .combinedClickable(
                                onClick = { onPhotoClick(photo) },
                                onLongClick = { onLongPress(photo.id) }
                            )
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(context).data(photo.uri).crossfade(true).size(200).build(),
                            contentDescription = photo.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        if (isMarked) {
                            Box(modifier = Modifier.fillMaxSize().background(Color.Red.copy(alpha = 0.3f)))
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp)
                            )
                        }
                        if (tabMode == 3) {
                            Box(
                                modifier = Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(topEnd = 6.dp)).padding(horizontal = 4.dp, vertical = 2.dp)
                            ) {
                                Text(formatSize(photo.size), color = Color.White, fontSize = 9.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── DUPLICATES TAB ────────────────────────────────────────

@Composable
fun DuplicatesTab(
    photos: List<Photo>,
    dupGroups: List<DupGroup>,
    dupThreshold: Float,
    onThresholdChange: (Float) -> Unit,
    isFinding: Boolean,
    markedForDeletion: List<Long>,
    onFindDups: () -> Unit,
    onAutoMark: () -> Unit,
    onToggleMark: (Long) -> Unit,
    onPhotoClick: (Photo) -> Unit,
    context: Context
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Duplicate Detection", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(4.dp))
            Text("Sensitivity: ${"%.1f".format(dupThreshold)} (lower = stricter)", fontSize = 13.sp, color = Color.Gray)
            Slider(
                value = dupThreshold,
                onValueChange = onThresholdChange,
                valueRange = 2f..25f,
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onFindDups,
                    modifier = Modifier.weight(1f),
                    enabled = !isFinding && photos.isNotEmpty(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isFinding) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (isFinding) "Finding..." else "Find Duplicates")
                }
                OutlinedButton(
                    onClick = onAutoMark,
                    modifier = Modifier.weight(1f),
                    enabled = dupGroups.isNotEmpty(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Auto-mark")
                }
            }
        }

        if (dupGroups.isEmpty() && !isFinding) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        if (photos.isEmpty()) "Scan photos first to find duplicates"
                        else "No duplicate groups found. Try increasing sensitivity.",
                        modifier = Modifier.padding(16.dp),
                        color = Color(0xFF2E7D32)
                    )
                }
            }
        }

        items(dupGroups.size) { groupIdx ->
            val group = dupGroups[groupIdx]
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Group ${groupIdx + 1} — ${group.photos.size} similar photos", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxWidth().height((((group.photos.size + 1) / 2) * 140).dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(group.photos, key = { it.id }) { photo ->
                            val isMarked = photo.id in markedForDeletion
                            val bestId = group.photos.maxByOrNull { it.width.toLong() * it.height.toLong() }?.id
                            Box(
                                modifier = Modifier
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .then(
                                        if (isMarked) Modifier.border(3.dp, Color.Red, RoundedCornerShape(8.dp))
                                        else if (photo.id == bestId) Modifier.border(3.dp, Color(0xFF2E7D32), RoundedCornerShape(8.dp))
                                        else Modifier
                                    )
                                    .combinedClickable(
                                        onClick = { onToggleMark(photo.id) },
                                        onLongClick = { onPhotoClick(photo) }
                                    )
                            ) {
                                AsyncImage(
                                    model = ImageRequest.Builder(context).data(photo.uri).crossfade(true).size(200).build(),
                                    contentDescription = photo.name,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                                if (isMarked) {
                                    Box(modifier = Modifier.fillMaxSize().background(Color.Red.copy(alpha = 0.3f)))
                                    Icon(Icons.Filled.Delete, contentDescription = null, tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp))
                                }
                                if (photo.id == bestId) {
                                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.align(Alignment.TopStart).padding(4.dp).size(20.dp))
                                }
                                Box(
                                    modifier = Modifier.align(Alignment.BottomStart).background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(topEnd = 6.dp)).padding(horizontal = 4.dp, vertical = 2.dp)
                                ) {
                                    Text("${photo.width}×${photo.height}", color = Color.White, fontSize = 9.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── OCR TAB ───────────────────────────────────────────────

@Composable
fun OcrTab(
    photos: List<Photo>,
    isOcrRunning: Boolean,
    ocrProgress: Float,
    ocrCurrent: Int,
    ocrTotal: Int,
    ocrQuery: String,
    onQueryChange: (String) -> Unit,
    onRunOcr: () -> Unit,
    onExport: () -> Unit,
    onPhotoClick: (Photo) -> Unit,
    onCopy: (String) -> Unit,
    context: Context
) {
    val pendingCount = photos.count { !it.ocrDone }
    val ocrDonePhotos = photos.filter { !it.ocrText.isNullOrBlank() }

    val searchResults = if (ocrQuery.isBlank()) {
        ocrDonePhotos
    } else {
        val words = ocrQuery.lowercase().split(" ").filter { it.isNotBlank() }
        ocrDonePhotos.filter { p ->
            val text = p.ocrText?.lowercase() ?: ""
            words.all { word -> text.contains(word) }
        }.sortedByDescending { p ->
            val text = p.ocrText?.lowercase() ?: ""
            words.sumOf { word ->
                var count = 0
                var startIdx = 0
                while (true) {
                    val idx = text.indexOf(word, startIdx)
                    if (idx < 0) break
                    count++
                    startIdx = idx + 1
                }
                count
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Text Recognition (OCR)", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(4.dp))
            Text("Read text in photos using on-device ML Kit", color = Color.Gray, fontSize = 13.sp)
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onRunOcr,
                    modifier = Modifier.weight(1f),
                    enabled = !isOcrRunning && pendingCount > 0,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isOcrRunning) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("$ocrCurrent / $ocrTotal", fontSize = 13.sp)
                    } else {
                        Text("Read text ($pendingCount left)", fontSize = 13.sp)
                    }
                }
                OutlinedButton(
                    onClick = onExport,
                    modifier = Modifier.weight(0.6f),
                    enabled = ocrDonePhotos.isNotEmpty(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Export", fontSize = 13.sp)
                }
            }
        }

        if (isOcrRunning) {
            item {
                LinearProgressIndicator(progress = { ocrProgress }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)))
            }
        }

        item {
            OutlinedTextField(
                value = ocrQuery,
                onValueChange = onQueryChange,
                label = { Text("Search text in photos") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
        }

        item {
            Text("${searchResults.size} photos with text found", color = Color.Gray, fontSize = 12.sp)
        }

        items(searchResults.take(100), key = { it.id }) { photo ->
            val ocrText = photo.ocrText ?: ""
            val snippet = if (ocrQuery.isBlank()) {
                ocrText.take(100)
            } else {
                val lowerText = ocrText.lowercase()
                val firstWord = ocrQuery.lowercase().split(" ").firstOrNull { lowerText.contains(it) } ?: ""
                val idx = lowerText.indexOf(firstWord)
                if (idx >= 0) {
                    val start = max(0, idx - 30)
                    val end = min(ocrText.length, idx + 70)
                    (if (start > 0) "..." else "") + ocrText.substring(start, end) + (if (end < ocrText.length) "..." else "")
                } else {
                    ocrText.take(100)
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().combinedClickable(
                        onClick = { onPhotoClick(photo) }
                    ).padding(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(photo.uri).crossfade(true).size(150).build(),
                        contentDescription = photo.name,
                        modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(photo.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(snippet, fontSize = 12.sp, color = Color.DarkGray, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { onCopy(ocrText) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(18.dp), tint = Color.Gray)
                    }
                }
            }
        }
    }
}