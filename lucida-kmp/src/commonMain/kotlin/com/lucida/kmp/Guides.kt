package com.lucida.kmp

import kotlin.jvm.Volatile
import kotlin.random.Random

/** A guide as `GET /v1/guides` serves it (the same definitions the browser SDK renders). */
data class MobileGuide(val id: String, val name: String, val kind: String, val steps: List<GuideStep>, val conditions: Obj, val preview: Boolean = false)

data class GuideStep(val id: String, val title: String?, val body: String?, val buttons: List<GuideButton>?)

/** action: next | dismiss | link | event (start_tour behaves like next on mobile). */
data class GuideButton(val label: String, val action: String, val url: String? = null, val event: String? = null)

/**
 * Port: draws one guide step natively, callable from any thread (the adapter hops to the main thread).
 * [onAction] gets the tapped button, or null when the person closed the step without one (a dismissal).
 */
interface GuidePresenter {
    fun present(guide: MobileGuide, step: Int, buttons: List<GuideButton>, onAction: (GuideButton?) -> Unit)
    fun open(url: String)
}

/** Off by default: no `/v1/guides` request until the app opts in. Android and iOS fill [presenter] with an alert. */
class GuidesConfig(@Volatile var enabled: Boolean = false, @Volatile var presenter: GuidePresenter? = null)

/**
 * In-app guides on mobile (LC-ENG-15), the sdks/android behaviour in common code. Kinds modal, banner, announcement,
 * slideout, tour (steps in order); step selectors are ignored. Targeting: url (screen name; url_match
 * exact/contains/regex), flag_key, sample_pct, devices (mobile or tablet), seen_guides / not_seen_guides. A guide using
 * any other targeting key is skipped. Each guide shows once per device; one at a time; preview guides are quiet, once
 * per launch. Events: $guide_shown, $guide_step, $guide_completed, $guide_dismissed with $guide_id.
 */
class GuidesService(
    private val core: Core,
    private val config: GuidesConfig,
    private val random: () -> Double = { Random.nextDouble() },
    private val flag: (String) -> Boolean,
) {
    private var guides: List<MobileGuide> = emptyList()
    private val seen: MutableSet<String> = HashSet()
    private val previewed = HashSet<String>()
    private var showing = false
    private var screen: String? = null

    init {
        core.storage.get(SEEN)?.let { raw -> runCatching { (Json.decode(raw) as List<*>).forEach { (it as? String)?.let(seen::add) } } }
    }

    /** Fetches the project's live guides (needs analytics consent), then re-evaluates the current screen. Blocks. */
    fun reload() {
        if (!config.enabled || !core.consent.has("analytics")) return
        val q = "api_key=${percentEncode(core.cfg.apiKey)}&distinct_id=${percentEncode(core.distinctId)}"
        val r = core.cfg.http.send("GET", "${core.host}/v1/guides?$q")
        if (r.status !in 200..299) return
        val list = (runCatching { Json.decodeObj(r.body) }.getOrNull()?.get("guides") as? List<*>) ?: emptyList<Any?>()
        @Suppress("UNCHECKED_CAST")
        val parsed = list.mapNotNull { runCatching { parse(it as Obj) }.getOrNull() }
        val at = locked {
            guides = parsed
            screen
        }
        if (at != null) show(at)
    }

    /** The app moved to [name]: show the first eligible guide, if none is on screen. */
    fun show(name: String) {
        val g = locked {
            screen = name
            if (showing) return
            val pick = guides.firstOrNull { eligible(it, name) } ?: return
            showing = true
            if (pick.preview) previewed.add(pick.id)
            pick
        }
        val p = config.presenter
        if (p == null) {
            locked { showing = false }
            return
        }
        if (!g.preview) {
            markSeen(g.id)
            core.capture("\$guide_shown", props(g))
        }
        present(p, g, 0)
    }

    private fun eligible(g: MobileGuide, name: String): Boolean {
        if (g.kind !in KINDS || g.steps.isEmpty()) return false
        if (g.preview) return g.id !in previewed
        val c = g.conditions
        // null, "" and [] are the editor's "not set"
        val used = c.filterValues { !(it == null || it == "" || (it is List<*> && it.isEmpty())) }.keys
        if (g.id in seen || !HANDLED.containsAll(used)) return false
        val url = c["url"] as? String
        if (!url.isNullOrEmpty() && !matches(name, url, c["url_match"] as? String ?: "contains")) return false
        val fk = c["flag_key"] as? String
        if (!fk.isNullOrEmpty() && !flag(fk)) return false
        val pct = c["sample_pct"] as? Number
        if (pct != null && random() * 100 >= pct.toDouble()) return false
        val devices = c["devices"] as? List<*>
        if (devices != null && devices.isNotEmpty() && devices.none { it == "mobile" || it == "tablet" }) return false
        val need = c["seen_guides"] as? List<*>
        if (need != null && !need.all { ((it as? String) ?: "") in seen }) return false
        val not = c["not_seen_guides"] as? List<*>
        if (not != null && not.any { ((it as? String) ?: "") in seen }) return false
        return true
    }

    private fun present(p: GuidePresenter, g: MobileGuide, i: Int) = p.present(g, i, buttons(g, i)) { handle(p, g, i, it) }

    private fun handle(p: GuidePresenter, g: MobileGuide, i: Int, b: GuideButton?) {
        val quiet = g.preview
        val stepProps: Obj = mapOf("\$guide_step_id" to g.steps[i].id, "\$guide_step_index" to i)
        if (b == null || b.action == "dismiss") {
            if (!quiet) core.capture("\$guide_dismissed", props(g, stepProps))
            return finish()
        }
        val action = b.action
        if (action == "link") b.url?.takeIf { it.startsWith("https://") }?.let(p::open)
        if (action == "event" && !quiet && !b.event.isNullOrEmpty()) core.capture(b.event, mapOf("\$guide_id" to g.id))
        if (!quiet) core.capture("\$guide_step", props(g, stepProps))
        if (i + 1 < g.steps.size && action != "link") return present(p, g, i + 1)
        if (!quiet) core.capture("\$guide_completed", props(g))
        finish()
    }

    private fun finish() {
        val at = locked {
            showing = false
            screen
        }
        if (at != null) show(at) // the next eligible guide, if any
    }

    private fun markSeen(id: String) {
        val list = locked {
            seen.add(id)
            seen.sorted()
        }
        core.storage.set(SEEN, Json.encode(list))
    }

    private fun props(g: MobileGuide, extra: Obj = emptyMap()): Obj = extra + mapOf("\$guide_id" to g.id, "\$guide_kind" to g.kind)

    @Suppress("UNCHECKED_CAST")
    private fun parse(o: Obj) = MobileGuide(
        id = o["id"] as String,
        name = o["name"] as String,
        kind = o["kind"] as String,
        steps = (o["steps"] as List<Obj>).map { s ->
            GuideStep(
                s["id"] as String, s["title"] as? String, s["body"] as? String,
                (s["buttons"] as? List<Obj>)?.map { GuideButton(it["label"] as String, it["action"] as String, it["url"] as? String, it["event"] as? String) },
            )
        },
        conditions = o["conditions"] as? Obj ?: emptyMap(),
        preview = o["preview"] == true,
    )

    companion object {
        private const val SEEN = "lc_guide_seen"
        val KINDS = setOf("modal", "banner", "announcement", "slideout", "tour")
        val HANDLED = setOf("url", "url_match", "flag_key", "sample_pct", "devices", "seen_guides", "not_seen_guides", "delay_seconds", "goal")

        fun matches(screen: String, pattern: String, how: String): Boolean = when (how) {
            "exact" -> screen == pattern
            "regex" -> runCatching { Regex(pattern).containsMatchIn(screen) }.getOrDefault(false)
            else -> screen.contains(pattern)
        }

        fun buttons(g: MobileGuide, i: Int): List<GuideButton> {
            g.steps[i].buttons?.takeIf { it.isNotEmpty() }?.let { return it }
            if (g.kind == "banner" || g.kind == "announcement") return listOf(GuideButton("Dismiss", "dismiss"))
            return listOf(GuideButton(if (i == g.steps.size - 1) "Done" else "Next", "next"), GuideButton("Dismiss", "dismiss"))
        }
    }
}
