package io.github.devngho.herewego.server

import io.github.devngho.herewego.server.store.Store
import io.github.devngho.herewego.server.api.HEADER_APP_KEY
import io.github.devngho.herewego.server.api.HEADER_DEVICE_ID
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationTest {

    private fun app(config: Config = Config(appKey = "secret"), block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application { module(config, search = null, llm = null, store = Store(":memory:")) }
            block()
        }

    @Test
    fun `healthz needs no headers`() = app {
        val res = client.get("/healthz")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("ok", res.bodyAsText())
    }

    @Test
    fun `v1 rejects a wrong app key`() = app {
        val res = client.get("/v1/purposes") {
            header(HEADER_APP_KEY, "wrong")
            header(HEADER_DEVICE_ID, "device-1234")
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
        assertTrue(res.bodyAsText(), "\"code\":\"UNAUTHORIZED\"" in res.bodyAsText())
    }

    @Test
    fun `v1 requires a device id`() = app {
        val res = client.get("/v1/purposes") { header(HEADER_APP_KEY, "secret") }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertTrue(res.bodyAsText(), "DEVICE_ID_REQUIRED" in res.bodyAsText())
    }

    @Test
    fun `purposes lists the presets`() = app {
        val res = client.get("/v1/purposes") {
            header(HEADER_APP_KEY, "secret")
            header(HEADER_DEVICE_ID, "device-1234")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val body = res.bodyAsText()
        assertTrue(body, "\"id\":\"HOUSEWARMING\"" in body && "\"label\":\"집들이\"" in body)
    }

    @Test
    fun `app key check is skipped when unset`() = app(Config(appKey = null)) {
        val res = client.get("/v1/purposes") { header(HEADER_DEVICE_ID, "device-1234") }
        assertEquals(HttpStatusCode.OK, res.status)
    }
}
