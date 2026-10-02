package com.brahmadeo.supertonic.tts.utils

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

class ResumableModelFileTest {
    private val data=ByteArray(600000) { (it%251).toByte() }
    private fun hash(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun server(cutFirst: Boolean, block: (String, List<Int>)->Unit) {
        val listener=ServerSocket(0)
        val offsets=CopyOnWriteArrayList<Int>()
        val thread=Thread {
            try {
                while(!listener.isClosed) listener.accept().use { socket ->
                    val reader=socket.getInputStream().bufferedReader();val headers=mutableListOf<String>()
                    while(true) { val line=reader.readLine() ?: break;if(line.isEmpty()) break;headers+=line }
                    val offset=headers.firstOrNull { it.startsWith("Range:",true) }?.substringAfter("bytes=")?.substringBefore('-')?.toInt() ?: 0
                    offsets+=offset
                    val response=if(offset>0) "206 Partial Content\r\nContent-Range: bytes $offset-${data.lastIndex}/${data.size}\r\n" else "200 OK\r\n"
                    val out=socket.getOutputStream()
                    out.write(("HTTP/1.1 $response"+"Content-Length: ${data.size-offset}\r\nConnection: close\r\n\r\n").toByteArray())
                    val length=if(cutFirst && offsets.size==1) 100000 else data.size-offset
                    out.write(data,offset,length);out.flush()
                }
            } catch(_: java.io.IOException) {}
        }.apply { isDaemon=true;start() }
        try { block("http://127.0.0.1:${listener.localPort}/model",offsets) }
        finally { listener.close();thread.join(2000) }
    }
    @Test fun interruptedTransferResumesAndVerifies() {
        val file=File.createTempFile("tts-model",".part")
        try { server(true) { url,offsets ->
            runBlocking { ResumableModelFile.fetch(url,file,data.size.toLong(),hash(data)) { _,_-> } }
            assertArrayEquals(data,file.readBytes())
            assertEquals(listOf(0,100000),offsets)
        } } finally { file.delete() }
    }
    @Test fun partialFileContinuesAfterRestart() {
        val file=File.createTempFile("tts-model",".part");file.writeBytes(data.copyOfRange(0,100000))
        try { server(false) { url,offsets ->
            runBlocking { ResumableModelFile.fetch(url,file,data.size.toLong(),hash(data)) { _,_-> } }
            assertArrayEquals(data,file.readBytes());assertEquals(listOf(100000),offsets)
        } } finally { file.delete() }
    }
    @Test fun invalidDigestDeletesUntrustedFile() {
        val file=File.createTempFile("tts-model",".part");file.writeBytes(data)
        try {
            try { runBlocking { ResumableModelFile.fetch("http://127.0.0.1:1",file,data.size.toLong(),"wrong") { _,_-> } };fail("Untrusted file installed") }
            catch(_: IllegalStateException) { assertFalse(file.exists()) }
        } finally { file.delete() }
    }
}
