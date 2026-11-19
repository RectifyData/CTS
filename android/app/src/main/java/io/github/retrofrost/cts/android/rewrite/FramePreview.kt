package io.github.retrofrost.cts.android.rewrite

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.retrofrost.cts.android.export.ReferenceFrameRenderer
import io.github.retrofrost.cts.android.model.CtsProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

/** A conflated, sequential stream: the renderer never loses a frame to seek cancellation. */
@Composable
internal fun FramePreview(project: CtsProject, position: Float, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val time = rememberUpdatedState(position)
    val width = 640
    val ratio = project.export.width.toFloat() / project.export.height.coerceAtLeast(1)
    val height = (width / ratio).toInt().coerceIn(120, 1280)
    val ready = project.cards.isNotEmpty() || project.rendererV3 != null
    var frame by remember(project) { mutableStateOf<Bitmap?>(null) }
    var error by remember(project) { mutableStateOf<String?>(null) }
    LaunchedEffect(project, width, height, ready) {
        if (!ready) return@LaunchedEffect
        var renderer: ReferenceFrameRenderer? = null
        try {
            withContext(Dispatchers.Default) {
                renderer = ReferenceFrameRenderer(context, project, width, height)
            }
            snapshotFlow { (time.value.coerceAtLeast(0f) * 30f).toInt() }
                .conflate().collect { tick ->
                    val bitmap = withContext(Dispatchers.Default) {
                        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                            try { renderer!!.render(it, tick / 30f) }
                            catch (e: Exception) { it.recycle(); throw e }
                        }
                    }
                    frame = bitmap
                }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            error = e.message ?: "The preview could not be rendered"
        } finally {
            withContext(NonCancellable + Dispatchers.Default) { renderer?.close() }
        }
    }
    Surface(modifier.aspectRatio(ratio), shape = MaterialTheme.shapes.medium, color = Color.Black) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val current = frame
            if (current != null && error == null) {
                Image(current.asImageBitmap(), "Video preview", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else Text(error ?: if (ready) "Preparing preview…" else "Add cards to begin",
                color = Color.White, modifier = Modifier.padding(16.dp))
        }
    }
}
