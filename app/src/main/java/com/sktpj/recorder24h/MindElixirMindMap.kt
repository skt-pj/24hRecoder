package com.sktpj.recorder24h

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private const val MIND_ELIXIR_ASSET_URL =
    "https://appassets.androidplatform.net/assets/mindmap/index.html"

private data class MindMapSourceNode(
    val source: JSONObject,
    val originalId: String,
    val id: String,
    val label: String,
    val parentId: String
)

@Composable
internal fun AiMindMapCard(
    mindMap: JSONArray?,
    links: JSONArray? = null,
    fallbackTopics: JSONArray? = null
) {
    val colorScheme = MaterialTheme.colorScheme
    val darkTheme = isSystemInDarkTheme()
    val mindMapKey = mindMap?.toString().orEmpty()
    val linksKey = links?.toString().orEmpty()
    val fallbackKey = fallbackTopics?.toString().orEmpty()
    val nodeCount = mindMap?.length()?.takeIf { it > 0 }
        ?: fallbackTopics?.length()?.takeIf { it > 0 }
        ?: 0
    if (nodeCount == 0) return

    val payload = remember(
        mindMapKey,
        linksKey,
        fallbackKey,
        darkTheme,
        colorScheme.primary,
        colorScheme.secondary,
        colorScheme.tertiary,
        colorScheme.surface,
        colorScheme.surfaceContainer,
        colorScheme.onSurface,
        colorScheme.onSurfaceVariant,
        colorScheme.outline,
        colorScheme.primaryContainer,
        colorScheme.onPrimaryContainer
    ) {
        buildMindElixirPayload(
            mindMap = mindMap,
            links = links,
            fallbackTopics = fallbackTopics,
            darkTheme = darkTheme,
            primary = colorScheme.primary,
            secondary = colorScheme.secondary,
            tertiary = colorScheme.tertiary,
            surface = colorScheme.surface,
            surfaceContainer = colorScheme.surfaceContainer,
            onSurface = colorScheme.onSurface,
            onSurfaceVariant = colorScheme.onSurfaceVariant,
            outline = colorScheme.outline,
            primaryContainer = colorScheme.primaryContainer,
            onPrimaryContainer = colorScheme.onPrimaryContainer
        ).toString()
    }

    val mapHeight = when {
        nodeCount <= 5 -> 340.dp
        nodeCount <= 9 -> 390.dp
        else -> 430.dp
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("マインドマップ", style = MaterialTheme.typography.titleLarge)
            Text(
                "ピンチで拡大・縮小、ドラッグで移動。枝は必要なところだけ開けます。ノードを選ぶと根拠を確認できます。",
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant
            )
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(mapHeight)
                    .clip(MaterialTheme.shapes.medium),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 1.dp
            ) {
                MindElixirWebView(
                    payload = payload,
                    background = colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MindElixirWebView(
    payload: String,
    background: Color,
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            val assetLoader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                .build()
            WebView(context).apply {
                setBackgroundColor(background.toArgb())
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = WebView.OVER_SCROLL_NEVER
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
                settings.setSupportZoom(false)
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? =
                        assetLoader.shouldInterceptRequest(request.url)

                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean = request.url.host != "appassets.androidplatform.net"

                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)
                        (view.tag as? String)?.let { renderMindMap(view, it) }
                    }
                }
                setOnTouchListener { view, event ->
                    view.parent?.requestDisallowInterceptTouchEvent(event.pointerCount > 0)
                    false
                }
                tag = payload
                loadUrl(MIND_ELIXIR_ASSET_URL)
            }
        },
        update = { webView ->
            webView.tag = payload
            webView.setBackgroundColor(background.toArgb())
            renderMindMap(webView, payload)
        },
        onRelease = { webView ->
            webView.stopLoading()
            webView.destroy()
        }
    )
}

private fun renderMindMap(webView: WebView, payload: String) {
    val quoted = JSONObject.quote(payload)
    webView.evaluateJavascript(
        "window.renderMindMap && window.renderMindMap(JSON.parse($quoted));",
        null
    )
}

private fun buildMindElixirPayload(
    mindMap: JSONArray?,
    links: JSONArray?,
    fallbackTopics: JSONArray?,
    darkTheme: Boolean,
    primary: Color,
    secondary: Color,
    tertiary: Color,
    surface: Color,
    surfaceContainer: Color,
    onSurface: Color,
    onSurfaceVariant: Color,
    outline: Color,
    primaryContainer: Color,
    onPrimaryContainer: Color
): JSONObject {
    val sourceObjects = mindMap.toObjectList()
    val normalized = ArrayList<MindMapSourceNode>()
    val usedIds = linkedSetOf<String>()
    val originalToNormalized = linkedMapOf<String, String>()

    sourceObjects.forEachIndexed { index, source ->
        val label = source.optString("label", "").trim()
        if (label.isEmpty()) return@forEachIndexed
        val originalId = source.optString("id", "").trim()
        val baseId = originalId.ifEmpty { "node-${index + 1}" }
        var id = baseId
        var suffix = 2
        while (!usedIds.add(id)) {
            id = "$baseId-$suffix"
            suffix++
        }
        if (originalId.isNotEmpty() && !originalToNormalized.containsKey(originalId)) {
            originalToNormalized[originalId] = id
        }
        normalized += MindMapSourceNode(
            source = source,
            originalId = originalId,
            id = id,
            label = label,
            parentId = source.optString("parentId", "").trim()
        )
    }

    if (normalized.isEmpty()) {
        val fallback = fallbackTopics.toStringList().take(5)
        val root = JSONObject()
            .put("id", "root")
            .put("label", "この時間の主な話題")
            .put("parentId", "")
            .put("type", "theme")
            .put("importance", "high")
            .put("detail", "")
            .put("evidence", "")
            .put("collapsedByDefault", false)
        normalized += MindMapSourceNode(root, "root", "root", "この時間の主な話題", "")
        fallback.forEachIndexed { index, topic ->
            val child = JSONObject()
                .put("id", "topic-${index + 1}")
                .put("label", topic)
                .put("parentId", "root")
                .put("type", "topic")
                .put("importance", "medium")
                .put("detail", "")
                .put("evidence", "")
                .put("collapsedByDefault", false)
            normalized += MindMapSourceNode(
                child,
                "topic-${index + 1}",
                "topic-${index + 1}",
                topic,
                "root"
            )
        }
        originalToNormalized["root"] = "root"
        fallback.indices.forEach { index ->
            originalToNormalized["topic-${index + 1}"] = "topic-${index + 1}"
        }
    }

    val idSet = normalized.mapTo(linkedSetOf()) { it.id }
    val normalizedParent = normalized.associate { node ->
        node.id to originalToNormalized[node.parentId].orEmpty()
    }
    val roots = normalized.filter { node ->
        val parent = normalizedParent[node.id].orEmpty()
        parent.isEmpty() || parent == node.id || parent !in idSet
    }
    val rootNode: JSONObject
    val buildRoots: List<MindMapSourceNode>
    val syntheticRoot = roots.size != 1
    if (syntheticRoot) {
        rootNode = JSONObject()
            .put("id", "pcs-root")
            .put("topic", "主なテーマ")
            .put("expanded", true)
            .put("metadata", JSONObject()
                .put("type", "theme")
                .put("importance", "high")
                .put("detail", "")
                .put("evidence", ""))
        buildRoots = if (roots.isNotEmpty()) roots else normalized.take(1)
    } else {
        rootNode = buildMindElixirNode(
            node = roots.first(),
            all = normalized,
            normalizedParent = normalizedParent,
            depth = 0,
            path = emptySet()
        )
        buildRoots = emptyList()
    }

    if (syntheticRoot) {
        val children = JSONArray()
        buildRoots.forEach { node ->
            children.put(
                buildMindElixirNode(
                    node = node,
                    all = normalized,
                    normalizedParent = normalizedParent,
                    depth = 1,
                    path = emptySet()
                )
            )
        }
        rootNode.put("children", children)
    }

    val arrows = JSONArray()
    links.toObjectList().take(4).forEachIndexed { index, link ->
        val fromOriginal = link.optString("fromId", link.optString("from", "")).trim()
        val toOriginal = link.optString("toId", link.optString("to", "")).trim()
        val from = originalToNormalized[fromOriginal] ?: fromOriginal.takeIf { it in idSet }
        val to = originalToNormalized[toOriginal] ?: toOriginal.takeIf { it in idSet }
        if (from.isNullOrEmpty() || to.isNullOrEmpty() || from == to) return@forEachIndexed
        arrows.put(
            JSONObject()
                .put("id", link.optString("id", "link-${index + 1}").ifBlank { "link-${index + 1}" })
                .put("label", link.optString("label", "関連").trim().take(24))
                .put("from", from)
                .put("to", to)
                .put("bidirectional", false)
                .put("style", JSONObject()
                    .put("stroke", outline.toCssHex())
                    .put("strokeWidth", 1.5)
                    .put("strokeDasharray", "6,4")
                    .put("strokeLinecap", "round")
                    .put("opacity", 0.78)
                    .put("labelColor", onSurfaceVariant.toCssHex()))
        )
    }

    val theme = JSONObject()
        .put("name", if (darkTheme) "24hRecoder Dark" else "24hRecoder Light")
        .put("type", if (darkTheme) "dark" else "light")
        .put("palette", JSONArray()
            .put(primary.toCssHex())
            .put(secondary.toCssHex())
            .put(tertiary.toCssHex())
            .put(primaryContainer.toCssHex())
            .put(outline.toCssHex()))
        .put("cssVar", JSONObject()
            .put("--main-color", onSurface.toCssHex())
            .put("--main-bgcolor", surface.toCssHex())
            .put("--main-bgcolor-transparent", surface.toCssHex())
            .put("--color", onSurface.toCssHex())
            .put("--bgcolor", surfaceContainer.toCssHex())
            .put("--selected", primary.toCssHex())
            .put("--accent-color", primary.toCssHex())
            .put("--root-color", onPrimaryContainer.toCssHex())
            .put("--root-bgcolor", primaryContainer.toCssHex())
            .put("--root-border-color", primary.toCssHex())
            .put("--root-radius", "14px")
            .put("--main-radius", "12px")
            .put("--topic-padding", "8px 11px")
            .put("--panel-color", onSurface.toCssRgb())
            .put("--panel-bgcolor", surface.toCssRgb())
            .put("--panel-border-color", outline.toCssRgb())
            .put("--map-padding", "28px"))

    return JSONObject()
        .put("data", JSONObject()
            .put("nodeData", rootNode)
            .put("arrows", arrows)
            .put("direction", 2)
            .put("compact", true)
            .put("theme", theme))
        .put("ui", JSONObject()
            .put("background", surfaceContainer.toCssHex())
            .put("surface", surface.toCssHex())
            .put("text", onSurface.toCssHex())
            .put("muted", onSurfaceVariant.toCssHex())
            .put("primary", primary.toCssHex())
            .put("outline", outline.toCssHex()))
}

private fun buildMindElixirNode(
    node: MindMapSourceNode,
    all: List<MindMapSourceNode>,
    normalizedParent: Map<String, String>,
    depth: Int,
    path: Set<String>
): JSONObject {
    if (node.id in path) {
        return JSONObject()
            .put("id", node.id)
            .put("topic", node.label.take(72))
            .put("expanded", false)
    }
    val source = node.source
    val type = source.optString("type", if (depth == 0) "theme" else "topic").trim().lowercase(Locale.ROOT)
    val importance = source.optString("importance", if (depth <= 1) "high" else "medium")
        .trim().lowercase(Locale.ROOT)
    val detail = source.optString("detail", "").trim()
    val evidence = source.optString("evidence", source.optString("evidenceTime", "")).trim()
    val collapsed = if (source.has("collapsedByDefault")) {
        source.optBoolean("collapsedByDefault", false)
    } else {
        depth >= 2 && importance != "high"
    }
    val objectNode = JSONObject()
        .put("id", node.id)
        .put("topic", node.label.take(72))
        .put("expanded", depth == 0 || !collapsed)
        .put("metadata", JSONObject()
            .put("type", type)
            .put("importance", importance)
            .put("detail", detail)
            .put("evidence", evidence))

    nodeTypeTag(type)?.let { objectNode.put("tags", JSONArray().put(it)) }

    val nextPath = path + node.id
    val children = all.filter { child -> normalizedParent[child.id] == node.id && child.id !in nextPath }
    if (children.isNotEmpty()) {
        val childArray = JSONArray()
        children.take(6).forEach { child ->
            childArray.put(
                buildMindElixirNode(
                    node = child,
                    all = all,
                    normalizedParent = normalizedParent,
                    depth = depth + 1,
                    path = nextPath
                )
            )
        }
        objectNode.put("children", childArray)
    }
    return objectNode
}

private fun nodeTypeTag(type: String): String? = when (type) {
    "decision" -> "決定"
    "action", "todo" -> "次にやる"
    "question", "unresolved" -> "未解決"
    "idea" -> "アイデア"
    else -> null
}

private fun JSONArray?.toObjectList(): List<JSONObject> {
    if (this == null) return emptyList()
    val result = ArrayList<JSONObject>(length())
    for (index in 0 until length()) {
        optJSONObject(index)?.let(result::add)
    }
    return result
}

private fun JSONArray?.toStringList(): List<String> {
    if (this == null) return emptyList()
    val result = ArrayList<String>(length())
    for (index in 0 until length()) {
        val value = optString(index, "").trim()
        if (value.isNotEmpty() && value != "null") result += value
    }
    return result
}

private fun Color.toCssHex(): String = String.format(
    Locale.US,
    "#%06X",
    toArgb() and 0xFFFFFF
)

private fun Color.toCssRgb(): String {
    val argb = toArgb()
    return "${AndroidColor.red(argb)}, ${AndroidColor.green(argb)}, ${AndroidColor.blue(argb)}"
}
