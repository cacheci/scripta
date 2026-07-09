package top.yukonga.scripta.sandbox

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

actual class DocumentOpener(private val launch: () -> Unit) {
    actual fun open() = launch()
}

@Composable
actual fun rememberDocumentOpener(
    onOpened: (name: String, content: String) -> Unit,
    onError: (message: String) -> Unit,
): DocumentOpener {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        readDocument(scope, context, uri, onOpened, onError)
    }
    return remember(launcher) { DocumentOpener { launcher.launch(arrayOf("*/*")) } }
}

private fun readDocument(
    scope: CoroutineScope,
    context: Context,
    uri: Uri,
    onOpened: (String, String) -> Unit,
    onError: (String) -> Unit,
) = scope.launch {
    try {
        val (name, content) = withContext(Dispatchers.IO) {
            val display = queryDisplayName(context, uri)
            val size = queryFileSize(context, uri)
            if (size > MAX_OPEN_BYTES) {
                throw IOException("文件过大（${size / 1024 / 1024}MB），上限 ${MAX_OPEN_BYTES / 1024 / 1024}MB")
            }
            val body = context.contentResolver.openInputStream(uri)
                ?.use { it.readBytes().decodeToString() }
                ?: throw IOException("无法读取文件")
            (display ?: "未命名") to body
        }
        onOpened(name, content)
    } catch (e: Exception) {
        onError("打开失败: ${e.message}")
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }

private fun queryFileSize(context: Context, uri: Uri): Long =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
    } ?: -1L

@Composable
actual fun SyncSystemBarsAppearance(dark: Boolean) {
    val view = LocalView.current
    LaunchedEffect(dark, view) {
        val window = (view.context as? android.app.Activity)?.window ?: return@LaunchedEffect
        val bars = WindowCompat.getInsetsController(window, view)
        bars.isAppearanceLightStatusBars = !dark
        bars.isAppearanceLightNavigationBars = !dark
    }
}
