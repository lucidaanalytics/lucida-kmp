package com.lucida.kmp

enum class ViewKind { CONTAINER, TEXT, INPUT, IMAGE, BUTTON }

/**
 * Platform-neutral view hierarchy node (the Android adapter converts android.view.View into this).
 * Coordinates are dp, relative to the parent's origin.
 */
data class ViewNode(
    val kind: ViewKind,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val text: String? = null,
    /** Short class or resource name, exposed as the element class (`lc-<name>`). */
    val name: String? = null,
    /** Privacy: text is starred unless the host explicitly opts a view out. Password inputs can never be unmasked. */
    val maskText: Boolean = true,
    /** Input values are starred while true (password fields always set it). */
    val maskInput: Boolean = true,
    /** Images carry `data-lc-mask`; the wireframe never holds pixels either way. */
    val maskImage: Boolean = true,
    /** Replaced by an empty placeholder box; the subtree is never serialised. */
    val block: Boolean = false,
    /** Password-like input: marker regions can never unmask it. */
    val neverUnmask: Boolean = false,
    /** Content the walker cannot read (a Compose view): screenshots mask it while text masking is on. */
    val opaque: Boolean = false,
    val children: List<ViewNode> = emptyList(),
)

/** Result of [Wireframe.snapshot]: the rrweb events plus a hit test over the serialised node ids. */
class Snapshot(val events: List<Obj>, private val boxes: List<Box>) {
    class Box(val id: Int, val left: Int, val top: Int, val right: Int, val bottom: Int)

    /** Id of the deepest serialised node containing the point (absolute dp), or the body. */
    fun nodeAt(x: Double, y: Double): Int = boxes.lastOrNull { x >= it.left && x < it.right && y >= it.top && y < it.bottom }?.id ?: BODY_ID

    companion object {
        const val BODY_ID = 5
    }
}

/**
 * View hierarchy -> mobile replay events in the rrweb shape that POST /v1/replay carries and
 * crates/lc-replay decodes: Meta (type 4, `href` = `app://<screen>`), FullSnapshot (type 2, DOM-like
 * node tree: document -> html -> head/body -> absolutely positioned elements) and incremental touch
 * events (type 3, source 2). Text is starred (same rule as lc-replay's `stars`: whitespace kept),
 * input values are always starred, blocked views carry `data-lc-block` and no children.
 */
object Wireframe {
    const val HREF_PREFIX = "app://"

    fun stars(s: String) = s.map { if (it.isWhitespace()) it else '*' }.joinToString("")

    fun snapshot(root: ViewNode, screen: String, width: Int, height: Int, ts: Long): Snapshot {
        var next = 6
        val boxes = ArrayList<Snapshot.Box>()

        fun build(v: ViewNode, ox: Int, oy: Int): Obj {
            val id = next++
            val left = ox + v.x
            val top = oy + v.y
            boxes.add(Snapshot.Box(id, left, top, left + v.width, top + v.height))
            val tag = when {
                v.block -> "div"
                v.kind == ViewKind.TEXT -> "span"
                v.kind == ViewKind.INPUT -> "input"
                v.kind == ViewKind.BUTTON -> "button"
                else -> "div"
            }
            val attrs = linkedMapOf<String, Any?>(
                "style" to "position:absolute;left:${v.x}px;top:${v.y}px;width:${v.width}px;height:${v.height}px;box-sizing:border-box;border:1px solid #bbb",
                "class" to "lc-${v.name ?: v.kind.name.lowercase()}",
            )
            val kids = ArrayList<Obj>()
            when {
                v.block -> attrs["data-lc-block"] = ""
                v.kind == ViewKind.INPUT -> {
                    attrs["type"] = "text"
                    attrs["value"] = if (v.maskInput) stars(v.text ?: "") else v.text ?: ""
                    if (v.maskInput) attrs["data-lc-mask"] = ""
                }
                else -> {
                    val t = v.text
                    if (!t.isNullOrEmpty()) {
                        if (v.maskText) attrs["data-lc-mask"] = ""
                        kids.add(mapOf("type" to 3, "textContent" to (if (v.maskText) stars(t) else t), "id" to next++))
                    }
                    if (v.kind == ViewKind.IMAGE) {
                        attrs["data-lc-image"] = ""
                        if (v.maskImage) attrs["data-lc-mask"] = ""
                    }
                    v.children.forEach { kids.add(build(it, left, top)) }
                }
            }
            return mapOf("type" to 2, "tagName" to tag, "attributes" to attrs, "childNodes" to kids, "id" to id)
        }

        val content = build(root, 0, 0)
        val body = mapOf(
            "type" to 2, "tagName" to "body", "id" to Snapshot.BODY_ID, "childNodes" to listOf(content),
            "attributes" to mapOf("style" to "position:relative;margin:0;width:${width}px;height:${height}px"),
        )
        val html = mapOf(
            "type" to 2, "tagName" to "html", "id" to 3, "attributes" to emptyMap<String, Any?>(),
            "childNodes" to listOf(mapOf("type" to 2, "tagName" to "head", "id" to 4, "attributes" to emptyMap<String, Any?>(), "childNodes" to emptyList<Obj>()), body),
        )
        val doc = mapOf(
            "type" to 0, "id" to 1,
            "childNodes" to listOf(mapOf("type" to 1, "name" to "html", "publicId" to "", "systemId" to "", "id" to 2), html),
        )
        val events = listOf(
            mapOf("type" to 4, "timestamp" to ts, "data" to mapOf("href" to HREF_PREFIX + screen, "width" to width, "height" to height)),
            mapOf("type" to 2, "timestamp" to ts, "data" to mapOf("node" to doc, "initialOffset" to mapOf("left" to 0, "top" to 0))),
        )
        return Snapshot(events, boxes)
    }

    /**
     * Screenshot mode: the same Meta + FullSnapshot shape, but the body holds one `<img>` with the already-masked JPEG
     * (masks were painted before encoding, on device), so the rrweb player renders it as-is.
     */
    fun screenshotSnapshot(jpegBase64: String, screen: String, width: Int, height: Int, ts: Long): Snapshot {
        val src = "data:image/jpeg;base64,$jpegBase64"
        val img = mapOf(
            "type" to 2, "tagName" to "img", "id" to 6, "childNodes" to emptyList<Obj>(),
            "attributes" to mapOf("src" to src, "style" to "width:${width}px;height:${height}px", "class" to "lc-screenshot"),
        )
        val body = mapOf(
            "type" to 2, "tagName" to "body", "id" to Snapshot.BODY_ID, "childNodes" to listOf(img),
            "attributes" to mapOf("style" to "position:relative;margin:0;width:${width}px;height:${height}px"),
        )
        val html = mapOf(
            "type" to 2, "tagName" to "html", "id" to 3, "attributes" to emptyMap<String, Any?>(),
            "childNodes" to listOf(mapOf("type" to 2, "tagName" to "head", "id" to 4, "attributes" to emptyMap<String, Any?>(), "childNodes" to emptyList<Obj>()), body),
        )
        val doc = mapOf(
            "type" to 0, "id" to 1,
            "childNodes" to listOf(mapOf("type" to 1, "name" to "html", "publicId" to "", "systemId" to "", "id" to 2), html),
        )
        val events = listOf(
            mapOf("type" to 4, "timestamp" to ts, "data" to mapOf("href" to HREF_PREFIX + screen, "width" to width, "height" to height)),
            mapOf("type" to 2, "timestamp" to ts, "data" to mapOf("node" to doc, "initialOffset" to mapOf("left" to 0, "top" to 0))),
        )
        return Snapshot(events, listOf(Snapshot.Box(6, 0, 0, width, height)))
    }

    /** A tap as an rrweb MouseInteraction Click (type 3, source 2, interaction type 2). */
    fun tap(snapshot: Snapshot, x: Double, y: Double, ts: Long): Obj =
        mapOf("type" to 3, "timestamp" to ts, "data" to mapOf("source" to 2, "type" to 2, "id" to snapshot.nodeAt(x, y), "x" to x, "y" to y))
}
