package com.brahmadeo.supertonic.tts.books.prepare

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Отказ облака (лимит, кончились средства, сеть, 5xx) ставит подготовку на паузу; неверный ключ и «ответ не уместился» — нет. */
class BookPreparationPauseTest {
    @Test fun limitsAndNetworkPause() {
        assertTrue(BookPreparation.outage(IllegalArgumentException("API HTTP 429: лимит запросов; повторите позже")))
        assertTrue(BookPreparation.outage(IllegalArgumentException("API HTTP 402: на счёте этого сервера закончились средства")))
        assertTrue(BookPreparation.outage(IllegalStateException("API HTTP 503")))
        assertTrue(BookPreparation.outage(java.net.SocketTimeoutException("timeout")))
        assertTrue(BookPreparation.outage(IllegalStateException("LLM превышен лимит запроса")))
    }

    @Test fun keyAndBookErrorsDoNotPause() {
        assertFalse(BookPreparation.outage(IllegalArgumentException("API HTTP 401: сервер отказал в доступе")))
        assertFalse(BookPreparation.outage(IllegalArgumentException("API HTTP 400")))
        assertFalse(BookPreparation.outage(IllegalStateException("LLM вернула ответ не в формате JSON")))
    }

    @Test fun aPauseIsACancellationSoBatchesDoNotSwallowIt() {
        val p = BookPreparation.pauseOr(IllegalArgumentException("API HTTP 429: лимит запросов"))
        assertTrue(p is java.util.concurrent.CancellationException)
        assertTrue(p is BookPreparation.CloudPause)
    }
}
