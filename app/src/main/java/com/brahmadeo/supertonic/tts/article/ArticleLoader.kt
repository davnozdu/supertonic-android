package com.brahmadeo.supertonic.tts.article

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

object ArticleLoader {
    private const val MAX_HTML = 3 * 1024 * 1024
    suspend fun load(link: String): ReadingArticle = withContext(Dispatchers.IO) {
        require(ArticleExtractor.validUrl(link)) { "Вставьте ссылку http:// или https:// на статью." }
        var url = URL(link)
        val deadline = System.nanoTime() + 30_000_000_000L
        repeat(6) {
            ensureActive()
            check(System.nanoTime() < deadline) { "Сайт отвечает слишком долго. Попробуйте ещё раз." }
            val connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 6_000; readTimeout = 7_000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/140.0 Mobile Safari/537.36 MyTTS/5")
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
                setRequestProperty("Accept-Encoding", "gzip")
            }
            try {
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    val target = URL(url, connection.getHeaderField("Location") ?: error("Некорректное перенаправление сайта."))
                    require(ArticleExtractor.validUrl(target.toString()) && !(url.protocol == "https" && target.protocol != "https")) {
                        "Неподдерживаемое перенаправление сайта."
                    }
                    url = target
                } else {
                    require(status in 200..299) { "Сайт вернул ошибку HTTP $status. Попробуйте передать текст статьи из браузера." }
                    val type = connection.contentType.orEmpty()
                    require(type.contains("html", true)) { "Ссылка должна вести на веб-статью, а не файл." }
                    val raw = connection.inputStream
                    val input = if (connection.contentEncoding.equals("gzip", true)) GZIPInputStream(raw) else raw
                    val bytes = input.use { stream ->
                        val out = ByteArrayOutputStream(); val buffer = ByteArray(16_384)
                        while (true) {
                            ensureActive()
                            check(System.nanoTime() < deadline) { "Сайт отвечает слишком долго." }
                            val size = stream.read(buffer)
                            if (size < 0) break
                            require(out.size() + size <= MAX_HTML) { "Страница слишком большая. Передайте текст статьи напрямую." }
                            out.write(buffer, 0, size)
                        }
                        out.toByteArray()
                    }
                    val charset = Regex("charset\\s*=\\s*[\"']?([^;\\s\"']+)", RegexOption.IGNORE_CASE).find(type)?.groupValues?.get(1)
                    // Jsoup detects BOM/meta charset when HTTP does not specify one.
                    val html = Jsoup.parse(bytes.inputStream(), charset, url.toString()).outerHtml()
                    ensureActive()
                    return@withContext ArticleExtractor.extract(html, url.toString())
                }
            } finally { connection.disconnect() }
        }
        error("Слишком много перенаправлений сайта.")
    }
}
