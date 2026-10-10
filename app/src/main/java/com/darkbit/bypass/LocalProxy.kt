package com.darkbit.bypass

import android.util.Log
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class LocalProxy(private val port: Int) {
    private var serverSocket: ServerSocket? = null
    @Volatile private var isRunning = false

    fun start() {
        try {
            serverSocket = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
            isRunning = true
            thread {
                while (isRunning) {
                    try {
                        val client = serverSocket?.accept() ?: continue
                        handleClient(client)
                    } catch (e: Exception) {
                        if (isRunning) Log.e("LocalProxy", "Accept error", e)
                    }
                }
            }
            Log.i("LocalProxy", "Started on 127.0.0.1:$port")
        } catch (e: Exception) {
            Log.e("LocalProxy", "Failed to start", e)
        }
    }

    private fun handleClient(client: Socket) {
        thread {
            var target: Socket? = null
            try {
                val input = client.getInputStream()
                val output = client.getOutputStream()
                
                val requestLine = readLine(input)
                if (requestLine.startsWith("CONNECT ")) {
                    val parts = requestLine.split(" ")
                    if (parts.size >= 2) {
                        val hostPort = parts[1].split(":")
                        val host = hostPort[0]
                        val targetPort = if (hostPort.size > 1) hostPort[1].toInt() else 443

                        // consume headers
                        while (true) {
                            val header = readLine(input)
                            if (header.isEmpty()) break
                        }

                        Log.i("LocalProxy", "Connecting to $host:$targetPort")
                        target = Socket(host, targetPort)
                        
                        output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                        output.flush()

                        val targetInput = target.getInputStream()
                        val targetOutput = target.getOutputStream()

                        val t1 = thread {
                            try { input.copyTo(targetOutput) } catch (e: Exception) {}
                            try { target.close() } catch (e: Exception) {}
                        }
                        val t2 = thread {
                            try { targetInput.copyTo(output) } catch (e: Exception) {}
                            try { client.close() } catch (e: Exception) {}
                        }
                        t1.join()
                        t2.join()
                        return@thread
                    }
                }
            } catch (e: Exception) {
                Log.e("LocalProxy", "Handler error", e)
            } finally {
                try { target?.close() } catch (e: Exception) {}
                try { client.close() } catch (e: Exception) {}
            }
        }
    }

    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        var c = input.read()
        while (c != -1 && c != '\n'.code) {
            if (c != '\r'.code) sb.append(c.toChar())
            c = input.read()
        }
        return sb.toString()
    }

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (e: Exception) {}
    }
}
