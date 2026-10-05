package de.jarvis.app.tools

import android.text.Html
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Websuche ohne Schlüssel: DuckDuckGo (Suchergebnisse), Google News RSS (Nachrichten), Wikipedia (Wissen).
 * Liest auch einzelne Seiten als Text.
 */
object WebTool : JarvisTool {
    override val name = "web"
    override val group = "core"
    override val feature: String? = null
    override val permissions = emptyList<String>()

    override val definition: JSONObject = JSONObject("""
{"name":"web","description":"Internet. search: Websuche (aktuelle Infos, Öffnungszeiten, Preise, Sport, Fakten). news: aktuelle Schlagzeilen (optional zu einem Thema). read: eine Seite aus den Ergebnissen lesen. Quellen kurz nennen, nichts erfinden.",
 "input_schema":{"type":"object","properties":{
  "action":{"type":"string","enum":["search","news","read"]},
  "query":{"type":"string"},"url":{"type":"string"}},"required":["action"]}}
""".trimIndent())

    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Mobile Safari/537.36"

    fun get(url: String, max: Int = 400_000, ua: String = UA): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 12000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", ua)
        c.setRequestProperty("Accept-Language", "de-DE,de;q=0.9")
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            val sb = StringBuilder()
            c.inputStream.bufferedReader().use { r ->
                val buf = CharArray(8192)
                while (sb.length < max) { val n = r.read(buf); if (n < 0) break; sb.append(buf, 0, n) }
            }
            return sb.toString()
        } finally { c.disconnect() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun text(html: String) = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().replace(Regex("\\s+"), " ").trim()

    override fun run(t: ToolContext, a: JSONObject): String {
        val q = a.optString("query")
        return try {
            when (a.optString("action", "search")) {
                "news" -> news(q)
                "read" -> read(a.optString("url"))
                else -> if (q.isBlank()) Res.error("Wonach soll ich suchen?") else search(q)
            }
        } catch (e: Exception) { Res.error("Internet gerade nicht erreichbar (${e.message}).") }
    }

    fun search(q: String): String {
        val out = mutableListOf<String>()
        try {
            val html = get("https://html.duckduckgo.com/html/?kl=de-de&q=" + enc(q))
            val links = Regex("""class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
            val snippets = Regex("""class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).findAll(html).map { text(it.groupValues[1]) }.toList()
            links.take(6).forEachIndexed { i, m ->
                var href = m.groupValues[1]
                Regex("uddg=([^&]+)").find(href)?.let { href = URLDecoder.decode(it.groupValues[1], "UTF-8") }
                if (href.startsWith("//")) href = "https:$href"
                if (href.contains("duckduckgo.com/y.js")) return@forEachIndexed // Werbung
                out += "${text(m.groupValues[2])} — ${snippets.getOrNull(i).orEmpty()} [${href}]"
            }
        } catch (_: Exception) { }
        if (out.isEmpty()) wiki(q)?.let { out += it }
        return if (out.isEmpty()) Res.error("Keine Suchergebnisse gefunden.")
        else Res.ok("Suchergebnisse zu „$q“:\n" + out.joinToString("\n"))
    }

    private fun wiki(q: String): String? = try {
        val j = JSONObject(get("https://de.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=3&srsearch=" + enc(q)))
        val hits = j.optJSONObject("query")?.optJSONArray("search") ?: return null
        (0 until hits.length()).joinToString("\n") { i ->
            val h = hits.getJSONObject(i)
            "Wikipedia: ${h.optString("title")} — ${text(h.optString("snippet"))} [https://de.wikipedia.org/wiki/${enc(h.optString("title").replace(' ', '_'))}]"
        }.ifBlank { null }
    } catch (_: Exception) { null }

    fun news(topic: String): String {
        val url = if (topic.isBlank()) "https://news.google.com/rss?hl=de&gl=DE&ceid=DE:de"
                  else "https://news.google.com/rss/search?hl=de&gl=DE&ceid=DE:de&q=" + enc(topic)
        val xml = get(url)
        val items = Regex("<item>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(xml).take(8).map { m ->
            val it = m.groupValues[1]
            fun tag(n: String) = Regex("<$n[^>]*>(.*?)</$n>", RegexOption.DOT_MATCHES_ALL).find(it)?.groupValues?.get(1)
                ?.replace("<![CDATA[", "")?.replace("]]>", "")?.let { s -> text(s) }.orEmpty()
            "${tag("title")} (${tag("pubDate").take(16)})"
        }.toList()
        return if (items.isEmpty()) Res.error("Keine Nachrichten gefunden.")
        else Res.ok("Aktuelle Schlagzeilen" + (if (topic.isNotBlank()) " zu „$topic“" else "") + " (Google News):\n" + items.joinToString("\n"))
    }

    private fun read(url: String): String {
        if (!url.startsWith("http")) return Res.error("Ungültige Adresse.")
        val html = get(url)
            .replace(Regex("(?is)<(script|style|noscript|svg|nav|footer|header)[^>]*>.*?</\\1>"), " ")
        val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)?.let { text(it) }.orEmpty()
        val body = text(html.replace(Regex("(?is)<head.*?</head>"), " ")).take(3500)
        return Res.ok("Seite: $title\n$body")
    }
}
