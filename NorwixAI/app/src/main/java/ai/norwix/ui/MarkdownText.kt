package ai.norwix.ui

import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.LinkResolver
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin

@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val link = MaterialTheme.colorScheme.primary.toArgb()
    val renderer = remember(context) {
        Markwon.builder(context)
            .usePlugin(TablePlugin.create(context))
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                    builder.linkResolver(object : LinkResolver {
                        override fun resolve(view: View, target: String) {
                            val uri = Uri.parse(target)
                            if (uri.scheme == "https" && uri.host != null && uri.userInfo == null) {
                                try { view.context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                                catch (_: Exception) { Toast.makeText(view.context, "No browser is available.", Toast.LENGTH_SHORT).show() }
                            } else Toast.makeText(view.context, "Only HTTPS links can be opened. Copy the text to inspect other links.", Toast.LENGTH_LONG).show()
                        }
                    })
                }
            }).build()
    }
    AndroidView(factory = { ctx -> TextView(ctx).apply {
        textSize = 16f
        setTextIsSelectable(true)
        setLineSpacing(5f, 1f)
        includeFontPadding = false
        // No image plugin: Markdown images cannot trigger hidden network requests.
    } }, modifier = modifier, update = { view ->
        view.setTextColor(color); view.setLinkTextColor(link)
        renderer.setMarkdown(view, markdown)
    })
}
