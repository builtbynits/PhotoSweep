@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.photosweep

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

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
    val histogramStr: String = "",
    val ocrText: String? = null,
    val ocrDone: Boolean = false,
    val blurScore: Double = 0.0,
    val folderName: String = "",
    val dateModified: Long = 0L
)

data class DupGroup(val photos: List<Photo>)

// ─── SQLite Persistence Helper ─────────────────────────────

class PhotoDbHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "photosweep_v15.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_PHOTOS = "photos"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_PHOTOS (
                id INTEGER PRIMARY KEY,
                uri TEXT NOT NULL,
                name TEXT,
                size INTEGER,
                width INTEGER,
                height INTEGER,
                dHash INTEGER,
                aHash INTEGER,
                histogram TEXT,
                blurScore REAL,
                folderName TEXT,
                dateModified INTEGER,
                ocrText TEXT,
                ocrDone INTEGER DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_folder ON $TABLE_PHOTOS(folderName)")
        db.execSQL("CREATE INDEX idx_date ON $TABLE_PHOTOS(dateModified)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_PHOTOS")
        onCreate(db)
    }

    fun getAllPhotos(): List<Photo> {
        val list = mutableListOf<Photo>()
        val db = readableDatabase
        val cursor = db.query(TABLE_PHOTOS, null, null, null, null, null, "dateModified DESC")
        cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow("id")
            val uriCol = c.getColumnIndexOrThrow("uri")
            val nameCol = c.getColumnIndexOrThrow("name")
            val sizeCol = c.getColumnIndexOrThrow("size")
            val wCol = c.getColumnIndexOrThrow("width")
            val hCol = c.getColumnIndexOrThrow("height")
            val dhCol = c.getColumnIndexOrThrow("dHash")
            val ahCol = c.getColumnIndexOrThrow("aHash")
            val histCol = c.getColumnIndexOrThrow("histogram")
            val blurCol = c.getColumnIndexOrThrow("blurScore")
            val folderCol = c.getColumnIndexOrThrow("folderName")
            val dateCol = c.getColumnIndexOrThrow("dateModified")
            val ocrTextCol = c.getColumnIndexOrThrow("ocrText")
            val ocrDoneCol = c.getColumnIndexOrThrow("ocrDone")

            while (c.moveToNext()) {
                list.add(
                    Photo(
                        id = c.getLong(idCol),
                        uri = Uri.parse(c.getString(uriCol)),
                        name = c.getString(nameCol) ?: "",
                        size = c.getLong(sizeCol),
                        width = c.getInt(wCol),
                        height = c.getInt(hCol),
                        dHash = c.getLong(dhCol),
                        aHash = c.getLong(ahCol),
                        histogramStr = c.getString(histCol) ?: "",
                        blurScore = c.getDouble(blurCol),
                        folderName = c.getString(folderCol) ?: "",
                        dateModified = c.getLong(dateCol),
                        ocrText = c.getString(ocrTextCol),
                        ocrDone = c.getInt(ocrDoneCol) == 1
                    )
                )
            }
        }
        return list
    }

    fun savePhotosBatch(photos: List<Photo>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (p in photos) {
                val cv = ContentValues().apply {
                    put("id", p.id)
                    put("uri", p.uri.toString())
                    put("name", p.name)
                    put("size", p.size)
                    put("width", p.width)
                    put("height", p.height)
                    put("dHash", p.dHash)
                    put("aHash", p.aHash)
                    put("histogram", p.histogramStr)
                    put("blurScore", p.blurScore)
                    put("folderName", p.folderName)
                    put("dateModified", p.dateModified)
                    put("ocrText", p.ocrText)
                    put("ocrDone", if (p.ocrDone) 1 else 0)
                }
                db.insertWithOnConflict(TABLE_PHOTOS, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun updateOcrResult(photoId: Long, ocrText: String?, ocrDone: Boolean) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("ocrText", ocrText)
            put("ocrDone", if (ocrDone) 1 else 0)
        }
        db.update(TABLE_PHOTOS, cv, "id = ?", arrayOf(photoId.toString()))
    }

    fun deletePhotos(ids: List<Long>) {
        if (ids.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (id in ids) {
                db.delete(TABLE_PHOTOS, "id = ?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clearAll() {
        val db = writableDatabase
        db.execSQL("DELETE FROM $TABLE_PHOTOS")
    }
}

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
            primary = Color(0xFF0D47A1),
            onPrimary = Color.White,
            secondary = Color(0xFF00B0FF),
            surface = Color(0xFFF8F9FA),
            background = Color.White,
            error = Color(0xFFD32F2F)
        ),
        content = content
    )
}

// ─── Image Processing Helpers ─────────────────────────────

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

fun computeHistogramStr(bmp: Bitmap): String {
    val scaled = Bitmap.createScaledBitmap(bmp, 32, 32, true)
    val bins = FloatArray(64)
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
    return bins.joinToString(",") { (it / total).toString() }
}

fun parseHistogram(str: String): List<Float> {
    if (str.isBlank()) return emptyList()
    return try {
        str.split(",").map { it.toFloat() }
    } catch (_: Exception) {
        emptyList()
    }
}

fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

fun histogramDiff(a: List<Float>, b: List<Float>): Float {
    if (a.size != b.size || a.isEmpty()) return 1f
    var sum = 0f
    for (i in a.indices) sum += abs(a[i] - b[i])
    return sum / 2f
}

fun similarityScore(p1: Photo, p2: Photo): Float {
    val dDist = hammingDistance(p1.dHash, p2.dHash)
    val aDist = hammingDistance(p1.aHash, p2.aHash)
    val hDiff = histogramDiff(parseHistogram(p1.histogramStr), parseHistogram(p2.histogramStr))
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
    return sum2 / count - mean * mean
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
    } catch (_: Exception) {
        null
    }
}

fun formatSize(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}

fun queryMediaStorePhotos(context: Context): List<Photo> {
    val list = mutableListOf<Photo>()
    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.SIZE,
        MediaStore.Images.Media.WIDTH,
        MediaStore.Images.Media.HEIGHT,
        MediaStore.Images.Media.DATA,
        MediaStore.Images.Media.DATE_MODIFIED
    )
    val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
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
        val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
        while (c.moveToNext()) {
            val id = c.getLong(idCol)
            val name = c.getString(nameCol) ?: "unknown"
            val size = c.getLong(sizeCol)
            val w = c.getInt(wCol)
            val h = c.getInt(hCol)
            val data = c.getString(dataCol)
            val dateMod = c.getLong(dateCol)
            val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            val folder = if (!data.isNullOrBlank()) File(data).parentFile?.name ?: "" else ""
            list.add(
                Photo(
                    id = id,
                    uri = uri,
                    name = name,
                    size = size,
                    width = w,
                    height = h,
                    folderName = folder,
                    dateModified = dateMod
                )
            )
        }
    }
    return list
}

// ─── Main App Composable ───────────────────────────────────

@Composable
fun PhotoSweepApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { PhotoDbHelper(context) }
    val haptic = LocalHapticFeedback.current

    var hasPermission by remember { mutableStateOf(false) }
    var permDenied by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }

    val photos = remember { mutableStateListOf<Photo>() }
    var isScanning by remember { mutableStateOf(false) }
    var scanProgress by remember { mutableFloatStateOf(0f) }
    var scanTotal by remember { mutableIntStateOf(0) }
    var scanCurrent by remember { mutableIntStateOf(0) }
    var lastScanSummary by remember { mutableStateOf("") }
    var lastScanTime by remember { mutableStateOf("") }

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
            db.deletePhotos(toRemove)
            dupGroups.clear()
            markedForDeletion.clear()
            Toast.makeText(context, "Deleted from storage & cache", Toast.LENGTH_SHORT).show()
        }
    }

    // ─── Initial Load from SQLite Cache ────────────────────
    LaunchedEffect(hasPermission) {
        if (hasPermission && photos.isEmpty()) {
            withContext(Dispatchers.IO) {
                val cached = db.getAllPhotos()
                if (cached.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        photos.addAll(cached)
                        lastScanSummary = "Loaded ${cached.size} photos instantly from SQLite cache"
                    }
                }
            }
        }
    }

    fun requestPerm() {
        permLauncher.launch(permToRequest)
    }

    // ─── Incremental Scan Engine ──────────────────────────
    fun runIncrementalScan(fullRescan: Boolean = false) {
        if (isScanning) return
        scope.launch {
            isScanning = true
            scanProgress = 0f

            if (fullRescan) {
                withContext(Dispatchers.IO) { db.clearAll() }
                photos.clear()
            }

            val devicePhotos = withContext(Dispatchers.IO) { queryMediaStorePhotos(context) }
            val existingMap = photos.associateBy { it.id }

            scanTotal = devicePhotos.size
            scanCurrent = 0

            val toProcess = mutableListOf<Photo>()
            val updatedPhotosList = mutableListOf<Photo>()
            val currentDeviceIds = devicePhotos.map { it.id }.toSet()

            // Remove deleted photos from database & state
            val deletedIds = existingMap.keys.filter { it !in currentDeviceIds }
            if (deletedIds.isNotEmpty()) {
                withContext(Dispatchers.IO) { db.deletePhotos(deletedIds) }
                photos.removeAll { it.id in deletedIds }
            }

            var reusedCount = 0
            for (dp in devicePhotos) {
                val existing = existingMap[dp.id]
                if (!fullRescan && existing != null && existing.dateModified == dp.dateModified && existing.size == dp.size) {
                    updatedPhotosList.add(existing)
                    reusedCount++
                } else {
                    toProcess.add(dp)
                }
            }

            val newlyProcessed = mutableListOf<Photo>()
            val batchSize = 20

            for (i in toProcess.indices step batchSize) {
                val end = min(i + batchSize, toProcess.size)
                val batch = toProcess.subList(i, end)
                val processedBatch = withContext(Dispatchers.Default) {
                    batch.map { photo ->
                        val bmp = loadSmallBitmap(context, photo.uri, 128)
                        if (bmp != null) {
                            val dh = computeDHash(bmp)
                            val ah = computeAHash(bmp)
                            val hist = computeHistogramStr(bmp)
                            val blur = computeBlurScore(bmp)
                            bmp.recycle()
                            photo.copy(dHash = dh, aHash = ah, histogramStr = hist, blurScore = blur)
                        } else {
                            photo
                        }
                    }
                }
                newlyProcessed.addAll(processedBatch)
                withContext(Dispatchers.IO) { db.savePhotosBatch(processedBatch) }
                scanCurrent = min(reusedCount + newlyProcessed.size, scanTotal)
                scanProgress = if (scanTotal > 0) scanCurrent.toFloat() / scanTotal else 1f
            }

            photos.clear()
            photos.addAll(updatedPhotosList + newlyProcessed)

            val sdf = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
            lastScanTime = sdf.format(Date())
            lastScanSummary = "Scanned ${newlyProcessed.size} new/updated • Reused $reusedCount cached"
            isScanning = false
            scanProgress = 1f
        }
    }

    // ─── Separate OCR Execution Task ───────────────────────
    fun runOcrTask() {
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
                    val text = if (bmp != null) {
                        val image = InputImage.fromBitmap(bmp, 0)
                        val result = recognizer.process(image).await()
                        bmp.recycle()
                        result.text.takeIf { it.isNotBlank() }
                    } else null

                    val idx = photos.indexOfFirst { it.id == p.id }
                    if (idx >= 0) {
                        val updated = photos[idx].copy(ocrText = text, ocrDone = true)
                        photos[idx] = updated
                        withContext(Dispatchers.IO) { db.updateOcrResult(p.id, text, true) }
                    }
                } catch (_: Exception) {
                    val idx = photos.indexOfFirst { it.id == p.id }
                    if (idx >= 0) {
                        photos[idx] = photos[idx].copy(ocrDone = true)
                        withContext(Dispatchers.IO) { db.updateOcrResult(p.id, null, true) }
                    }
                }
                ocrCurrent++
                ocrProgress = if (ocrTotal > 0) ocrCurrent.toFloat() / ocrTotal else 1f
            }
            isOcrRunning = false
            ocrProgress = 1f
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

    fun autoMarkDuplicates() {
        markedForDeletion.clear()
        for (group in dupGroups) {
            val best = group.photos.maxByOrNull { it.width.toLong() * it.height.toLong() }
            for (p in group.photos) {
                if (p.id != best?.id && p.id !in markedForDeletion) {
                    markedForDeletion.add(p.id)
                }
            }
        }
    }

    fun deleteMarkedPhotos() {
        if (markedForDeletion.isEmpty()) return
        val uris = markedForDeletion.mapNotNull { id -> photos.find { it.id == id }?.uri }
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
                    if (context.contentResolver.delete(uri, null, null) > 0) count++
                } catch (_: Exception) {}
            }
            val toRemove = markedForDeletion.toList()
            photos.removeAll { it.id in toRemove }
            db.deletePhotos(toRemove)
            dupGroups.clear()
            markedForDeletion.clear()
            Toast.makeText(context, "Deleted $count photos", Toast.LENGTH_SHORT).show()
        }
    }

    fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("OCR Text", text))
        Toast.makeText(context, "Copied OCR text", Toast.LENGTH_SHORT).show()
    }

    fun exportOcrText() {
        scope.launch(Dispatchers.IO) {
            try {
                val sb = StringBuilder().apply {
                    appendLine("PhotoSweep OCR Export")
                    appendLine("=".repeat(40))
                    for (p in photos) {
                        if (!p.ocrText.isNullOrBlank()) {
                            appendLine("\n--- ${p.name} ---")
                            appendLine(p.ocrText)
                        }
                    }
                }
                val text = sb.toString()
                if (Build.VERSION.SDK_INT >= 29) {
                    val cv = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "PhotoSweep_OCR.txt")
                        put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS)
                    }
                    val uri = context.contentResolver.insert(MediaStore.Files.getContentUri("external"), cv)
                    uri?.let { context.contentResolver.openOutputStream(it)?.use { os -> os.write(text.toByteArray()) } }
                } else {
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                    dir.mkdirs()
                    FileOutputStream(File(dir, "PhotoSweep_OCR.txt")).use { it.write(text.toByteArray()) }
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
                Icon(Icons.Filled.CleaningServices, contentDescription = null, modifier = Modifier.size(80.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(24.dp))
                Text("PhotoSweep v1.5", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text("Persistent SQLite Cache • Incremental Scan • Offline OCR", textAlign = TextAlign.Center, color = Color.Gray)
                Spacer(Modifier.height(32.dp))
                Button(onClick = { requestPerm() }, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) {
                    Text("Grant Storage Access", fontSize = 16.sp)
                }
                if (permDenied) {
                    Spacer(Modifier.height(16.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)), modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Permission denied. Please grant photo permission in Settings to run PhotoSweep.",
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
                shape = RoundedCornerShape(20.dp),
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
                    Text("${pp.width} × ${pp.height} • ${formatSize(pp.size)}", color = Color.Gray, fontSize = 12.sp)
                    Text("Blur score: ${"%.1f".format(pp.blurScore)}", color = if (pp.blurScore in 0.01..100.0) Color(0xFFE65100) else Color.Gray, fontSize = 12.sp)
                    Text("Folder: ${pp.folderName}", color = Color.Gray, fontSize = 12.sp)
                    if (!pp.ocrText.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text("OCR Text:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(pp.ocrText, fontSize = 12.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(onClick = { copyToClipboard(pp.ocrText) }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Copy Text", fontSize = 12.sp)
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
    val markedTotalSize = photos.filter { it.id in markedForDeletion }.sumOf { it.size }

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
                            onClick = { deleteMarkedPhotos() },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                            modifier = Modifier.padding(end = 8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                            Spacer(Modifier.width(4.dp))
                            Text("${markedForDeletion.size} (${formatSize(markedTotalSize)})", color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFFF1F3F4)) {
                val tabs = listOf(
                    "Home" to Icons.Filled.Home,
                    "Gallery" to Icons.Filled.Image,
                    "Dupes" to Icons.Filled.PhotoLibrary,
                    "OCR" to Icons.Filled.TextSnippet
                )
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
                    lastScanSummary = lastScanSummary,
                    lastScanTime = lastScanTime,
                    dupGroupsCount = dupGroups.size,
                    ocrDoneCount = photos.count { it.ocrDone && !it.ocrText.isNullOrBlank() },
                    onQuickScan = { runIncrementalScan(fullRescan = false) },
                    onFullRescan = { runIncrementalScan(fullRescan = true) },
                    onClearCache = {
                        db.clearAll()
                        photos.clear()
                        dupGroups.clear()
                        markedForDeletion.clear()
                        lastScanSummary = "SQLite index cleared"
                        Toast.makeText(context, "SQLite index cleared", Toast.LENGTH_SHORT).show()
                    },
                    onPhotoClick = { previewPhoto = it },
                    context = context
                )
                1 -> GalleryTab(
                    photos = photos,
                    markedForDeletion = markedForDeletion,
                    onPhotoClick = { previewPhoto = it },
                    onLongPress = { id ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (id in markedForDeletion) markedForDeletion.remove(id) else markedForDeletion.add(id)
                    },
                    onSelectAllVisible = { visibleIds ->
                        for (id in visibleIds) { if (id !in markedForDeletion) markedForDeletion.add(id) }
                    },
                    onUnselectAllVisible = { visibleIds ->
                        markedForDeletion.removeAll(visibleIds)
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
                    onAutoMark = { autoMarkDuplicates() },
                    onToggleMark = { id ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (id in markedForDeletion) markedForDeletion.remove(id) else markedForDeletion.add(id)
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
                    onRunOcr = { runOcrTask() },
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
    lastScanSummary: String,
    lastScanTime: String,
    dupGroupsCount: Int,
    ocrDoneCount: Int,
    onQuickScan: () -> Unit,
    onFullRescan: () -> Unit,
    onClearCache: () -> Unit,
    onPhotoClick: (Photo) -> Unit,
    context: Context
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("PhotoSweep Engine", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (photos.isNotEmpty()) "${photos.size} photos saved in SQLite database" else "Tap Quick Scan to index photos into SQLite",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 13.sp
                    )
                    if (lastScanTime.isNotBlank()) {
                        Text("Last scan: $lastScanTime", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                    }
                }
            }
        }

        if (lastScanSummary.isNotBlank()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD)), shape = RoundedCornerShape(12.dp)) {
                    Text(lastScanSummary, modifier = Modifier.padding(12.dp), fontSize = 12.sp, color = Color(0xFF0D47A1))
                }
            }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("Indexed", "${photos.size}", Icons.Filled.Image, Color(0xFF1565C0), Modifier.weight(1f))
                StatCard("OCR Read", "$ocrDoneCount", Icons.Filled.TextSnippet, Color(0xFF2E7D32), Modifier.weight(1f))
                StatCard("Duplicates", "$dupGroupsCount", Icons.Filled.PhotoLibrary, Color(0xFFE65100), Modifier.weight(1f))
            }
        }

        item {
            val ssCount = photos.count { it.folderName.equals("Screenshots", ignoreCase = true) }
            val blurryCount = photos.count { it.blurScore in 0.01..100.0 }
            val largeSize = photos.sortedByDescending { it.size }.take(50).sumOf { it.size }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("Screenshots", "$ssCount", Icons.Filled.Image, Color(0xFF7B1FA2), Modifier.weight(1f))
                StatCard("Blurry", "$blurryCount", Icons.Filled.Image, Color(0xFFC62828), Modifier.weight(1f))
                StatCard("Top50 Size", formatSize(largeSize), Icons.Filled.Image, Color(0xFF00695C), Modifier.weight(1f))
            }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onQuickScan,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    enabled = !isScanning
                ) {
                    if (isScanning) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("$scanCurrent / $scanTotal", fontSize = 12.sp)
                    } else {
                        Icon(Icons.Filled.FlashOn, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Quick Scan", fontSize = 14.sp)
                    }
                }

                OutlinedButton(
                    onClick = onFullRescan,
                    modifier = Modifier.weight(0.8f).height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    enabled = !isScanning
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Full Rescan", fontSize = 12.sp)
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
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Recent Photos", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    TextButton(onClick = onClearCache) { Text("Clear Cache Index", fontSize = 11.sp, color = Color.Gray) }
                }
            }

            item {
                val recentPhotos = photos.take(30)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxWidth().height(360.dp),
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
                                .combinedClickable(onClick = { onPhotoClick(photo) }),
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
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.08f)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(4.dp))
            Text(value, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = color)
            Text(label, fontSize = 10.sp, color = color.copy(alpha = 0.7f))
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
    onSelectAllVisible: (List<Long>) -> Unit,
    onUnselectAllVisible: (List<Long>) -> Unit,
    context: Context
) {
    var filterMode by remember { mutableIntStateOf(0) }

    val filteredPhotos = remember(photos.size, filterMode) {
        when (filterMode) {
            1 -> photos.filter { it.folderName.equals("Screenshots", ignoreCase = true) }
            2 -> photos.filter { it.blurScore in 0.01..100.0 }
            3 -> photos.sortedByDescending { it.size }.take(50)
            else -> photos
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf("All (${photos.size})", "Screenshots", "Blurry", "Top 50 Size").forEachIndexed { idx, label ->
                FilterChip(
                    selected = filterMode == idx,
                    onClick = { filterMode = idx },
                    label = { Text(label, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = Color.White
                    )
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${filteredPhotos.size} items", fontSize = 12.sp, color = Color.Gray)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        val ids = filteredPhotos.map { it.id }
                        onSelectAllVisible(ids)
                    },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("Select All", fontSize = 11.sp)
                }
                TextButton(
                    onClick = {
                        val ids = filteredPhotos.map { it.id }
                        onUnselectAllVisible(ids)
                    },
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text("Unselect All", fontSize = 11.sp)
                }
            }
        }

        if (filteredPhotos.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No items match filter. Run Quick Scan!", color = Color.Gray)
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
                            Box(modifier = Modifier.fillMaxSize().background(Color.Red.copy(alpha = 0.35f)))
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp)
                            )
                        }
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
            Text("Duplicate & Similar Photos", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(4.dp))
            Text("Sensitivity: ${"%.1f".format(dupThreshold)} (lower = stricter)", fontSize = 12.sp, color = Color.Gray)
            Slider(value = dupThreshold, onValueChange = onThresholdChange, valueRange = 2f..25f, modifier = Modifier.fillMaxWidth())
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onFindDups,
                    modifier = Modifier.weight(1f).height(48.dp),
                    enabled = !isFinding && photos.isNotEmpty(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isFinding) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (isFinding) "Analyzing..." else "Find Duplicates")
                }
                OutlinedButton(
                    onClick = onAutoMark,
                    modifier = Modifier.weight(1f).height(48.dp),
                    enabled = dupGroups.isNotEmpty(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Auto-Mark Dups")
                }
            }
        }

        if (dupGroups.isEmpty() && !isFinding) {
            item {
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)), shape = RoundedCornerShape(12.dp)) {
                    Text(
                        if (photos.isEmpty()) "Scan photos first to find duplicates" else "No duplicate groups found. Try adjusting sensitivity.",
                        modifier = Modifier.padding(16.dp),
                        color = Color(0xFF2E7D32)
                    )
                }
            }
        }

        items(dupGroups.size) { groupIdx ->
            val group = dupGroups[groupIdx]
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Group ${groupIdx + 1} — ${group.photos.size} similar photos", fontWeight = FontWeight.Bold, fontSize = 14.sp)
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
                                    Box(modifier = Modifier.fillMaxSize().background(Color.Red.copy(alpha = 0.35f)))
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

    val searchResults = remember(ocrQuery, photos.size, ocrDonePhotos.size) {
        if (ocrQuery.isBlank()) {
            ocrDonePhotos
        } else {
            val words = ocrQuery.lowercase().split(" ").filter { it.isNotBlank() }
            ocrDonePhotos.filter { p ->
                val text = p.ocrText?.lowercase() ?: ""
                words.all { word -> text.contains(word) }
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
            Text("On-device ML Kit text indexer • Offline & Private", color = Color.Gray, fontSize = 12.sp)
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onRunOcr,
                    modifier = Modifier.weight(1f).height(48.dp),
                    enabled = !isOcrRunning && pendingCount > 0,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isOcrRunning) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("$ocrCurrent / $ocrTotal", fontSize = 12.sp)
                    } else {
                        Text("Read Text ($pendingCount left)", fontSize = 12.sp)
                    }
                }
                OutlinedButton(
                    onClick = onExport,
                    modifier = Modifier.weight(0.6f).height(48.dp),
                    enabled = ocrDonePhotos.isNotEmpty(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Export", fontSize = 12.sp)
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
                label = { Text("Search text inside photos") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
        }

        item {
            Text("${searchResults.size} photos matching text query", color = Color.Gray, fontSize = 12.sp)
        }

        items(searchResults.take(100), key = { it.id }) { photo ->
            val ocrText = photo.ocrText ?: ""
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { onPhotoClick(photo) }).padding(10.dp),
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
                        Text(photo.name, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(ocrText, fontSize = 12.sp, color = Color.DarkGray, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { onCopy(ocrText) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(18.dp), tint = Color.Gray)
                    }
                }
            }
        }
    }
}