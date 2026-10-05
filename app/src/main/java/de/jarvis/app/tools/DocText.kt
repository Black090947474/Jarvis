package de.jarvis.app.tools

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Text aus Dokumenten holen: TXT/MD/CSV/JSON, DOCX, XLSX, PDF (über PDFBox). Alles auf dem Handy. */
object DocText {
    val TEXT_EXT = setOf("txt", "md", "csv", "json", "log", "xml", "html", "htm")
    val DOC_EXT = TEXT_EXT + setOf("docx", "xlsx", "pdf", "pptx")

    fun ext(name: String) = name.substringAfterLast('.', "").lowercase()

    fun fromFile(ctx: Context, f: File, max: Int = 12_000): String = f.inputStream().use { extract(ctx, it, f.name, max) }

    fun fromUri(ctx: Context, uri: Uri, name: String, max: Int = 12_000): String =
        ctx.contentResolver.openInputStream(uri)?.use { extract(ctx, it, name, max) } ?: ""

    fun extract(ctx: Context, input: InputStream, name: String, max: Int): String = when (ext(name)) {
        "docx" -> zipText(input, Regex("word/document\\.xml"), "</w:p>").take(max)
        "pptx" -> zipText(input, Regex("ppt/slides/slide\\d+\\.xml"), "</a:p>").take(max)
        "xlsx" -> xlsx(input).take(max)
        "pdf" -> pdf(ctx, input, max)
        else -> input.bufferedReader().use { r -> val cb = CharArray(max); val n = r.read(cb); if (n > 0) String(cb, 0, n) else "" }
    }

    private fun stripXml(xml: String, para: String) = xml.replace(para, "\n").replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        .replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()

    private fun zipText(input: InputStream, entry: Regex, para: String): String {
        val sb = StringBuilder()
        ZipInputStream(input).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (entry.matches(e.name)) sb.append(stripXml(z.readBytes().toString(Charsets.UTF_8), para)).append("\n")
                e = z.nextEntry
            }
        }
        return sb.toString()
    }

    private fun xlsx(input: InputStream): String {
        var shared = listOf<String>()
        val sheets = sortedMapOf<String, String>()
        ZipInputStream(input).use { z ->
            var e = z.nextEntry
            while (e != null) {
                val txt = if (e.name == "xl/sharedStrings.xml" || e.name.startsWith("xl/worksheets/sheet")) z.readBytes().toString(Charsets.UTF_8) else null
                if (e.name == "xl/sharedStrings.xml" && txt != null)
                    shared = Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL).findAll(txt).map { stripXml(it.groupValues[1], "") }.toList()
                else if (txt != null) sheets[e.name] = txt
                e = z.nextEntry
            }
        }
        val sb = StringBuilder()
        for ((n, xml) in sheets) {
            sb.append("Tabelle ${n.substringAfterLast('/').removeSuffix(".xml")}:\n")
            for (row in Regex("<row[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
                val cells = Regex("<c([^>]*)>(.*?)</c>", RegexOption.DOT_MATCHES_ALL).findAll(row.groupValues[1]).map { c ->
                    val v = Regex("<v>(.*?)</v>").find(c.groupValues[2])?.groupValues?.get(1)
                        ?: Regex("<t[^>]*>(.*?)</t>").find(c.groupValues[2])?.groupValues?.get(1) ?: ""
                    if (c.groupValues[1].contains("t=\"s\"")) shared.getOrNull(v.toIntOrNull() ?: -1) ?: v else v
                }.toList()
                if (cells.any { it.isNotBlank() }) sb.append(cells.joinToString(" | ")).append('\n')
            }
        }
        return sb.toString()
    }

    private fun pdf(ctx: Context, input: InputStream, max: Int): String = try {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(ctx.applicationContext)
        com.tom_roush.pdfbox.pdmodel.PDDocument.load(input).use { doc ->
            val s = com.tom_roush.pdfbox.text.PDFTextStripper()
            s.startPage = 1; s.endPage = minOf(doc.numberOfPages, 30)
            val t = s.getText(doc).trim()
            if (t.isBlank()) "(Dieses PDF enthält keinen Text – vermutlich ein eingescanntes Bild. Schick mir davon ein Foto im Chat.)" else t.take(max)
        }
    } catch (e: Throwable) { "(PDF konnte nicht gelesen werden: ${e.message})" }
}
