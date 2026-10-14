package io.legado.app.web

import android.app.Application
import com.google.gson.JsonParser
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.repository.BookshelfSettingsRepository
import io.legado.app.domain.gateway.BookshelfSettingsGateway
import io.legado.app.utils.GSON
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import splitties.init.injectAsAppCtx
import java.net.HttpURLConnection
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class KtorServerTest {

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        application.injectAsAppCtx()
        stopKoin()
        val fakeGateway = object : BookshelfSettingsGateway {
            override val currentSettings = io.legado.app.domain.model.settings.BookshelfSettings()
            override val settings = kotlinx.coroutines.flow.flowOf(io.legado.app.domain.model.settings.BookshelfSettings())
            override suspend fun update(transform: (io.legado.app.domain.model.settings.BookshelfSettings) -> io.legado.app.domain.model.settings.BookshelfSettings) {}
        }
        startKoin {
            modules(module {
                single<BookshelfSettingsGateway> { fakeGateway }
            })
        }
        val book = Book(
            name = "Test Book",
            author = "Test Author",
            bookUrl = "http://example.com/test_book"
        )
        appDb.bookDao.deleteAll()
        appDb.bookDao.insert(book)
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    private fun request(
        urlStr: String,
        method: String = "GET",
        body: String? = null,
        headers: Map<String, String> = emptyMap()
    ): Triple<Int, String, String?> {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = method
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (body != null) {
            conn.doOutput = true
            conn.outputStream.bufferedWriter().use { it.write(body) }
        }
        conn.connect()
        val code = conn.responseCode
        val contentType = conn.contentType
        val responseBody = if (code in 200..299) {
            conn.inputStream.bufferedReader().readText()
        } else {
            conn.errorStream?.bufferedReader()?.readText() ?: ""
        }
        return Triple(code, responseBody, contentType)
    }

    @Test
    fun testRequests() = runBlocking {
        val port = 11228
        val server = KtorServer(port)
        server.start()

        val hutoolHeaders = mapOf(
            "Accept" to "text/html,application/json,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "User-Agent" to "Mozilla/5.0 Hutool"
        )

        try {
            // 1. Standard /getBookshelf
            val (c1, b1, ct1) = request("http://127.0.0.1:$port/getBookshelf", headers = hutoolHeaders)
            println("Test 1 (/getBookshelf) -> $c1 [$ct1]: $b1")
            assertEquals(200, c1)
            assertTrue("Expected application/json, got $ct1", ct1?.contains("application/json") == true)
            assertTrue("Expected isSuccess true", b1.contains("\"isSuccess\":true"))
            assertTrue("Expected Test Book", b1.contains("Test Book"))

            // 2. Query parameters like plugin sends
            val (c2, b2, ct2) = request("http://127.0.0.1:$port/getBookshelf?source=legado-reader&accessToken=nanchueng", headers = hutoolHeaders)
            println("Test 2 (/getBookshelf?params) -> $c2 [$ct2]: $b2")
            assertEquals(200, c2)
            assertTrue(b2.contains("\"isSuccess\":true"))

            // 3. Double slashes (when address ends with slash + /getBookshelf)
            val (c3, b3, ct3) = request("http://127.0.0.1:$port//getBookshelf?source=legado-reader&accessToken=nanchueng", headers = hutoolHeaders)
            println("Test 3 (//getBookshelf?params) -> $c3 [$ct3]: $b3")
            assertEquals(200, c3)
            assertTrue(b3.contains("\"isSuccess\":true"))

            // 4. Trailing slash (/getBookshelf/)
            val (c4, b4, ct4) = request("http://127.0.0.1:$port/getBookshelf/?source=legado-reader&accessToken=nanchueng", headers = hutoolHeaders)
            println("Test 4 (/getBookshelf/?params) -> $c4 [$ct4]: $b4")
            assertEquals(200, c4)
            assertTrue(b4.contains("\"isSuccess\":true"))

            // 5. POST /saveBookProgress
            val postJson = """{"name":"Test Book","author":"Test Author","durChapterIndex":1,"durChapterPos":0,"durChapterTime":123456,"durChapterTitle":"第1章"}"""
            val (c5, b5, ct5) = request("http://127.0.0.1:$port/saveBookProgress", "POST", postJson, mapOf("Content-Type" to "application/json;charset=UTF-8"))
            println("Test 5 (POST /saveBookProgress) -> $c5 [$ct5]: $b5")
            assertEquals(200, c5)
            assertTrue(b5.contains("\"isSuccess\":true"))

            // 6. Unknown endpoint returns JSON error rather than 404 raw text
            val (c6, b6, ct6) = request("http://127.0.0.1:$port/nonExistentEndpoint")
            println("Test 6 (/nonExistentEndpoint) -> $c6 [$ct6]: $b6")

        } finally {
            server.stop()
        }
    }

    @Test
    fun pluginBookshelfSupportsDuplicateBooksAndSavesTheSelectedBook() = runBlocking {
        val original = appDb.bookDao.getBook("http://example.com/test_book")!!
        val recent = original.copy(
            bookUrl = "http://example.com/recent_book",
            durChapterTime = original.durChapterTime + 1,
        )
        appDb.bookDao.insert(recent)
        val server = KtorServer(11229)
        server.start()
        try {
            val (webCode, webBody) = request("http://127.0.0.1:11229/getBookshelf")
            assertEquals(200, webCode)
            assertEquals(2, JsonParser.parseString(webBody).asJsonObject.getAsJsonArray("data").size())

            val (pluginCode, pluginBody) = request("http://127.0.0.1:11229/getBookshelf?source=legado-reader")
            assertEquals(200, pluginCode)
            val pluginBooks = JsonParser.parseString(pluginBody).asJsonObject.getAsJsonArray("data")
            assertEquals(1, pluginBooks.size())
            assertEquals(recent.bookUrl, pluginBooks[0].asJsonObject["bookUrl"].asString)
            assertEquals(2, appDb.bookDao.all.size)

            val progress = """{"url":"${recent.bookUrl}","name":"Test Book","author":"Test Author","durChapterIndex":7,"durChapterPos":12,"durChapterTime":123456,"durChapterTitle":"Chapter 7"}"""
            val (saveCode, saveBody) = request(
                "http://127.0.0.1:11229/saveBookProgress", "POST", progress,
                mapOf("Content-Type" to "application/json"),
            )
            assertEquals(200, saveCode)
            assertTrue(JsonParser.parseString(saveBody).asJsonObject["isSuccess"].asBoolean)
            assertEquals(7, appDb.bookDao.getBook(recent.bookUrl)!!.durChapterIndex)
            assertEquals(12, appDb.bookDao.getBook(recent.bookUrl)!!.durChapterPos)
            assertEquals(original.durChapterIndex, appDb.bookDao.getBook(original.bookUrl)!!.durChapterIndex)

            val (_, missingBookBody) = request(
                "http://127.0.0.1:11229/saveBookProgress", "POST",
                progress.replace(recent.bookUrl, "http://example.com/missing_book"),
                mapOf("Content-Type" to "application/json"),
            )
            assertTrue(!JsonParser.parseString(missingBookBody).asJsonObject["isSuccess"].asBoolean)
            assertEquals(original.durChapterIndex, appDb.bookDao.getBook(original.bookUrl)!!.durChapterIndex)
        } finally {
            server.stop()
        }
    }
}
