package com.devfahim00.yulp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.webkit.URLUtil
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Built-in multi-thread download engine.
 *
 * - Splits a file into N parts, each part downloaded on its own connection
 *   with an HTTP Range request (IDM/aria2 style).
 * - Writes into the public Downloads folder via MediaStore (no storage
 *   permission needed on API 29+).
 * - Runs inside a foreground service so downloads keep going when the app
 *   is in the background; survives process death (progress is persisted
 *   every ~2s and auto-resumed by the service).
 * - Pause / resume / cancel / restart + thread-count selection.
 */
object DownloadEngine {

    enum class Status { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED }

    /** HLS (m3u8) state for a task. */
    class HlsInfo(
        @Volatile var variantUrl: String = "",
        @Volatile var segDone: Int = 0,
        @Volatile var segTotal: Int = 0,
        var keyUrl: String = "",
        var keyIvHex: String = "",
        var initUrl: String = ""
    )

    class Part(val start: Long, @Volatile var end: Long) { // end inclusive; end<0 => open-ended single stream
        @Volatile var downloaded = 0L
        fun remaining(): Long = if (end < 0) Long.MAX_VALUE else end - start + 1 - downloaded
        fun done(): Boolean = if (end < 0) false else downloaded >= end - start + 1
    }

    class Task(
        val id: Long,
        val url: String,
        var name: String,
        var mime: String,
        val headers: Map<String, String>,
        var threads: Int
    ) {
        @Volatile var status = Status.QUEUED
        @Volatile var total = -1L
        @Volatile var downloaded = 0L
        @Volatile var speed = 0L
        var supportsRange = false
        var uri: Uri? = null
        var parts = mutableListOf<Part>()
        @Volatile var error: String? = null
        var addedAt = System.currentTimeMillis()
        var finishedAt = 0L
        val futures = mutableListOf<Future<*>>()
        @Volatile var lastTickBytes = 0L
        var hls: HlsInfo? = null

        fun progressPct(): Int =
            if (total > 0) ((downloaded.coerceAtMost(total)) * 100 / total).toInt() else 0

        fun isActive() = status == Status.RUNNING || status == Status.QUEUED
    }

    interface Listener {
        fun onChanged(tasks: List<Task>)
    }

    val THREAD_OPTIONS = intArrayOf(1, 2, 4, 6, 8, 16)
    private const val MIN_PART_SIZE = 512L * 1024 // 512 KB minimum per thread
    private const val CHANNEL = "downloads"
    private const val SUMMARY_NOTIF = 1001

    private lateinit var appCtx: Context
    private val main = Handler(Looper.getMainLooper())
    private val exec = Executors.newFixedThreadPool(16)
    private val ticker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val io = Executors.newSingleThreadExecutor()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()

    private val tasks = mutableListOf<Task>()
    private val idGen = AtomicLong(System.currentTimeMillis())
    private var service: DownloadService? = null
    private var inited = false
    @Volatile private var dirty = false

    private val prefs by lazy { appCtx.getSharedPreferences("yulp_downloads", Context.MODE_PRIVATE) }

    var defaultThreads: Int
        get() = prefs.getInt("threads", 4).coerceIn(1, 16)
        set(v) = prefs.edit().putInt("threads", v.coerceIn(1, 16)).apply()

    // ---------------------------------------------------------------- init

    @Synchronized
    fun init(ctx: Context, autoResume: Boolean): DownloadEngine {
        if (!inited) {
            appCtx = ctx.applicationContext
            inited = true
            createChannel()
            load()
            ticker.scheduleWithFixedDelay({ tick() }, 400, 500, TimeUnit.MILLISECONDS)
        }
        if (autoResume) {
            tasks.filter { it.status == Status.RUNNING || it.status == Status.QUEUED }
                .forEach { resumeInternal(it) }
            ensureService()
        }
        return this
    }

    private fun createChannel() {
        val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Download progress"
                setShowBadge(false)
            })
    }

    // ---------------------------------------------------------------- enqueue / control

    fun enqueue(
        url: String, suggestedName: String?, mime: String?,
        headers: Map<String, String> = emptyMap(), threads: Int = defaultThreads,
        hls: Boolean = false
    ): Task {
        var name = suggestedName?.takeIf { it.isNotBlank() }
            ?: URLUtil.guessFileName(url, null, mime) ?: "download"
        name = name.replace(Regex("[\\/:*?\"<>|]"), "_")
        val m = mime?.takeIf { it.isNotBlank() } ?: "application/octet-stream"

        val t = Task(idGen.incrementAndGet(), url, name, m, headers, threads.coerceIn(1, 16))
        if (hls || url.substringBefore('?').endsWith(".m3u8")) t.hls = HlsInfo()
        synchronized(tasks) { tasks.add(0, t) }

        // create the target row in MediaStore Downloads (must be off the main thread)
        io.execute {
            try {
                t.uri = createRow(t)
                t.status = Status.RUNNING
                persist()
                startDownload(t)
                ensureService()
                notifyListeners()
            } catch (e: Exception) {
                t.error = e.message ?: "Cannot create file"
                t.status = Status.FAILED
                persist()
                notifyListeners()
            }
        }
        notifyListeners()
        return t
    }

    fun pause(t: Task) {
        if (!t.isActive()) return
        t.status = Status.PAUSED
        t.futures.forEach { it.cancel(true) }
        t.futures.clear()
        t.speed = 0
        persist()
        notifyListeners()
        ensureServiceStop()
    }

    fun resume(t: Task) {
        if (t.status == Status.RUNNING || t.status == Status.QUEUED) return
        if (t.status == Status.COMPLETED) return
        t.error = null
        resumeInternal(t)
        ensureService()
        notifyListeners()
    }

    private fun resumeInternal(t: Task) {
        if (t.status == Status.RUNNING) return
        if (t.uri == null) { // never started / lost row -> fresh enqueue
            io.execute {
                try {
                    t.uri = createRow(t)
                    t.status = Status.RUNNING
                    persist(); startDownload(t); notifyListeners()
                } catch (e: Exception) {
                    t.status = Status.FAILED; t.error = e.message; notifyListeners()
                }
            }
            return
        }
        t.status = Status.RUNNING
        if (t.hls != null) { startDownload(t); persist(); return }
        if (t.parts.isEmpty()) startDownload(t)
        else {
            // drop fully finished parts; resubmit the rest
            var any = false
            synchronized(t.parts) {
                val live = t.parts.filter { !it.done() && it.end >= 0 || it.end < 0 }
                if (live.isEmpty()) { finalize(t, true); return }
            }
            t.futures.clear()
            t.parts.forEach { p ->
                if (p.remaining() > 0 || p.end < 0) {
                    any = true
                    t.futures.add(exec.submit { partWorker(t, p) })
                }
            }
            if (!any) finalize(t, true)
        }
        persist()
    }

    /** Cancel + delete the partial file + remove from the list. */
    fun cancel(t: Task) {
        t.status = Status.PAUSED
        t.futures.forEach { it.cancel(true) }
        t.futures.clear()
        t.uri?.let { u ->
            io.execute {
                try { appCtx.contentResolver.delete(u, null, null) } catch (_: Exception) {}
            }
        }
        synchronized(tasks) { tasks.remove(t) }
        persist()
        notifyListeners()
        ensureServiceStop()
    }

    /** Remove a finished/failed task from the list (file stays on disk). */
    fun remove(t: Task) {
        if (t.isActive()) cancel(t)
        synchronized(tasks) { tasks.remove(t) }
        persist()
        notifyListeners()
    }

    fun clearFinished() {
        synchronized(tasks) { tasks.removeAll { it.status == Status.COMPLETED || it.status == Status.FAILED } }
        persist()
        notifyListeners()
    }

    fun restart(t: Task) {
        val url = t.url; val name = t.name; val mime = t.mime; val h = t.headers
        cancel(t)
        enqueue(url, name, mime, h, defaultThreads)
    }

    /**
     * Change the thread count of a paused task: finished parts stay, the
     * remaining bytes of unfinished parts are re-split into more (or fewer)
     * ranges so the next resume uses the new parallelism.
     */
    fun resplit(t: Task, n: Int) {
        t.threads = n.coerceIn(1, 16)
        if (t.isActive() || t.total <= 0 || !t.supportsRange) { persist(); return }
        synchronized(t.parts) {
            val finished = t.parts.filter { it.done() }
            val unfinished = t.parts
                .filter { !it.done() && it.end >= 0 }
                .map { Part(it.start, it.end).apply { downloaded = it.downloaded } }
                .toMutableList()
            while (unfinished.size < t.threads) {
                val big = unfinished.maxByOrNull { it.remaining() } ?: break
                val rem = big.remaining()
                if (rem < 2 * MIN_PART_SIZE) break
                val splitAt = big.start + big.downloaded + rem / 2
                unfinished.add(Part(splitAt, big.end))
                big.end = splitAt - 1
            }
            t.parts.clear()
            t.parts.addAll(finished)
            t.parts.addAll(unfinished)
            t.parts.sortBy { it.start }
        }
        persist()
        notifyListeners()
    }

    fun list(): List<Task> = synchronized(tasks) { tasks.toList() }

    fun openIntent(t: Task): Intent? {
        val uri = t.uri ?: return null
        return try {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, t.mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } catch (_: Exception) { null }
    }

    // ---------------------------------------------------------------- download internals

    private fun createRow(t: Task): Uri {
        val resolver = appCtx.contentResolver
        var name = t.name
        var n = 1
        // avoid silent overwrite: pick first free name in Downloads
        while (true) {
            val taken = resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
                arrayOf(name, Environment.DIRECTORY_DOWNLOADS + "/"), null
            )?.use { it.count > 0 } ?: false
            if (!taken) break
            val dot = t.name.lastIndexOf('.')
            name = if (dot > 0) "${t.name.substring(0, dot)} ($n)${t.name.substring(dot)}"
            else "${t.name} ($n)"
            n++
        }
        t.name = name
        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, t.mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        return resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            ?: throw IOException("Cannot create download file")
    }

    private fun openConnection(t: Task, rangeFrom: Long = -1, rangeTo: Long = -1): HttpURLConnection {
        val c = URL(t.url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.instanceFollowRedirects = true
        t.headers.forEach { (k, v) -> c.addRequestProperty(k, v) }
        if (rangeFrom >= 0) {
            c.addRequestProperty("Range",
                if (rangeTo >= 0) "bytes=$rangeFrom-$rangeTo" else "bytes=$rangeFrom-")
        }
        return c
    }

    /** Probe size + range support, split parts, spawn workers. Runs on exec/io thread. */
    private fun startDownload(t: Task) {
        if (t.hls != null) { hlsStart(t); return }
        exec.execute {
            try {
                // probe with a 1-byte range request
                val c = openConnection(t, 0, 0)
                val code = c.responseCode
                val cr = c.getHeaderField("Content-Range") // bytes 0-0/12345
                val len = c.contentLengthLong
                c.disconnect()
                when {
                    code == 206 && cr != null -> {
                        t.supportsRange = true
                        t.total = cr.substringAfter('/').toLongOrNull() ?: -1L
                    }
                    code in 200..399 -> {
                        t.supportsRange = false
                        t.total = if (len > 0) len else -1L
                    }
                    else -> throw IOException("HTTP $code")
                }

                synchronized(t.parts) {
                    t.parts.clear()
                    val resumeOffsets = HashMap<Long, Long>() // start -> downloaded
                    // (fresh download: empty)
                    if (t.total > 0 && t.supportsRange) {
                        var n = t.threads
                        while (n > 1 && t.total / n < MIN_PART_SIZE) n--
                        val base = t.total / n
                        var rem = t.total % n
                        var start = 0L
                        for (i in 0 until n) {
                            var len2 = base + if (rem > 0) { rem--; 1 } else 0
                            val end = start + len2 - 1
                            t.parts.add(Part(start, end))
                            start += len2
                        }
                    } else {
                        t.parts.add(Part(0, -1)) // open-ended stream
                    }
                }
                t.downloaded = t.parts.sumOf { it.downloaded }
                submitRemaining(t)
                notifyListeners()
            } catch (e: Exception) {
                if (t.isActive()) {
                    t.status = Status.FAILED
                    t.error = e.message ?: "Failed"
                    persist()
                    notifyListeners()
                    ensureServiceStop()
                }
            }
        }
    }

    private fun submitRemaining(t: Task) {
        t.futures.clear()
        var any = false
        synchronized(t.parts) {
            for (p in t.parts) {
                if (p.remaining() > 0 || p.end < 0) {
                    any = true
                    t.futures.add(exec.submit { partWorker(t, p) })
                }
            }
        }
        if (!any) finalize(t, true)
    }

    private fun partWorker(t: Task, p: Part) {
        var attempt = 0
        while (t.status == Status.RUNNING && (p.remaining() > 0 || p.end < 0)) {
            try {
                var from = p.start + p.downloaded
                val conn = openConnection(
                    t, from, if (p.end >= 0) p.end else -1
                )
                val code = conn.responseCode
                if (code !in 200..399) throw IOException("HTTP $code")
                // 200 on a ranged request => server ignored Range
                if (code == 200 && from > 0) {
                    if (p.end >= 0) throw IOException("Range not supported")
                    // open-ended stream, server restarted from byte 0 -> redo whole file
                    p.downloaded = 0
                    from = 0
                }
                if (p.end < 0 && p.downloaded == 0L && code == 200 && conn.contentLengthLong > 0
                    && t.total < 0) t.total = conn.contentLengthLong

                val pfd = appCtx.contentResolver.openFileDescriptor(
                    t.uri ?: throw IOException("No target file"), "rw"
                ) ?: throw IOException("Cannot open file")
                val channel = java.io.FileOutputStream(pfd.fileDescriptor).channel
                channel.position(from)
                try {
                    conn.inputStream.use { ins ->
                        val buf = ByteArray(64 * 1024)
                        while (t.status == Status.RUNNING) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            channel.write(java.nio.ByteBuffer.wrap(buf, 0, n))
                            p.downloaded += n
                        }
                    }
                } finally {
                    try { channel.close() } catch (_: Exception) {}
                    try { pfd.close() } catch (_: Exception) {}
                    try { conn.disconnect() } catch (_: Exception) {}
                }

                if (t.status != Status.RUNNING) return   // paused

                if (p.end < 0) {
                    // single stream finished at EOF
                    if (t.total < 0) t.total = p.downloaded
                    finalize(t, true)
                    return
                }
                if (p.remaining() > 0) throw IOException("Connection ended early")
                if (allDone(t)) { finalize(t, true); return }
                return
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                if (t.status != Status.RUNNING) return
                attempt++
                if (attempt > 3) {
                    t.status = Status.FAILED
                    t.error = e.message ?: "Failed"
                    persist()
                    notifyListeners()
                    ensureServiceStop()
                    return
                }
                try { Thread.sleep(1200L * attempt) } catch (_: InterruptedException) { return }
            }
        }
    }

    private fun allDone(t: Task): Boolean =
        synchronized(t.parts) { t.parts.all { it.done() } }

    // ---------------------------------------------------------------- HLS engine

    /** Fetch text with task headers (playlists / keys). */
    private fun hlsFetchText(t: Task, url: String): String? {
        return try {
            val c = openConnection(t)
            if (c.responseCode !in 200..399) null
            else c.inputStream.bufferedReader().use { it.readText() }.also { c.disconnect() }
        } catch (_: Exception) { null }
    }

    private fun hlsFetchBytes(t: Task, url: String): ByteArray? {
        return try {
            val c = openConnection(t)
            if (c.responseCode !in 200..399) null
            else c.inputStream.use { it.readBytes() }.also { c.disconnect() }
        } catch (_: Exception) { null }
    }

    private fun hlsResolve(base: String, uri: String): String = try {
        java.net.URI(base).resolve(uri).toString()
    } catch (_: Exception) { uri }

    /** Parse a master playlist -> variant list (url, bandwidth, w, h). */
    private fun hlsParseVariants(text: String, base: String): List<Array<Any>> {
        val out = mutableListOf<Array<Any>>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val l = lines[i].trim()
            if (l.startsWith("#EXT-X-STREAM-INF")) {
                val bw = Regex("BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val res = Regex("RESOLUTION=(\\d+)x(\\d+)").find(l)?.groupValues
                var j = i + 1
                while (j < lines.size && (lines[j].isBlank() || lines[j].startsWith("#"))) j++
                if (j < lines.size) {
                    out.add(arrayOf(hlsResolve(base, lines[j].trim()), bw,
                        (res?.get(1)?.toIntOrNull() ?: 0), (res?.get(2)?.toIntOrNull() ?: 0)))
                    i = j
                }
            }
            i++
        }
        return out
    }

    /**
     * HLS pipeline: fetch playlist (master -> best variant), then download
     * segments with N parallel workers while a writer thread appends them in
     * order to the target file. AES-128 encrypted streams are decrypted.
     */
    private fun hlsStart(t: Task) {
        exec.execute {
            try {
                var plUrl = t.url
                var text = hlsFetchText(t, plUrl) ?: throw IOException("Cannot fetch playlist")
                if (text.contains("#EXT-X-STREAM-INF")) {
                    val best = hlsParseVariants(text, plUrl).maxByOrNull { (it[1] as Long) }
                    if (best != null) plUrl = best[0] as String
                    t.hls?.variantUrl = plUrl
                    text = hlsFetchText(t, plUrl) ?: throw IOException("Cannot fetch variant playlist")
                } else {
                    t.hls?.variantUrl = plUrl
                }

                val lines = text.lines()
                val segs = mutableListOf<String>()
                var keyUrl = ""
                var keyIv = ""
                var initUrl = ""
                var i = 0
                while (i < lines.size) {
                    val l = lines[i].trim()
                    when {
                        l.startsWith("#EXT-X-KEY") -> {
                            keyUrl = Regex("URI=\"([^\"]+)\"").find(l)?.groupValues?.get(1)
                                ?.let { hlsResolve(plUrl, it) } ?: ""
                            keyIv = Regex("IV=0[xX]([0-9a-fA-F]+)").find(l)?.groupValues?.get(1) ?: ""
                        }
                        l.startsWith("#EXT-X-MAP") -> {
                            initUrl = Regex("URI=\"([^\"]+)\"").find(l)?.groupValues?.get(1)
                                ?.let { hlsResolve(plUrl, it) } ?: ""
                        }
                        l.startsWith("#EXTINF") -> {
                            var j = i + 1
                            while (j < lines.size && (lines[j].isBlank() || lines[j].startsWith("#"))) j++
                            if (j < lines.size) segs.add(hlsResolve(plUrl, lines[j].trim()))
                        }
                    }
                    i++
                }
                if (segs.isEmpty()) throw IOException("No segments in playlist")

                val skip = (t.hls?.segDone ?: 0).coerceIn(0, segs.size)
                t.hls?.segTotal = segs.size
                t.hls?.keyUrl = keyUrl
                t.hls?.keyIvHex = keyIv
                t.hls?.initUrl = initUrl
                val key: ByteArray? = if (keyUrl.isNotEmpty()) hlsFetchBytes(t, keyUrl) else null
                val remain = segs.subList(skip, segs.size)
                t.total = -1
                notifyListeners()
                if (remain.isEmpty()) { finalize(t, true); return@execute }

                val pending = java.util.concurrent.ConcurrentHashMap<Int, ByteArray>()
                val nextIdx = java.util.concurrent.atomic.AtomicInteger(0)
                val uri = t.uri ?: throw IOException("No target file")

                // ordered writer
                t.futures.add(exec.submit {
                    try {
                        val pfd = appCtx.contentResolver.openFileDescriptor(uri, "rw")
                            ?: throw IOException("Cannot open file")
                        val ch = java.io.FileOutputStream(pfd.fileDescriptor).channel
                        var offset = 0L
                        try {
                            // fMP4 init segment first
                            if (skip == 0 && initUrl.isNotEmpty()) {
                                val init = hlsFetchBytes(t, initUrl)
                                if (init != null) { ch.write(java.nio.ByteBuffer.wrap(init)); offset += init.size }
                            }
                            for (k in remain.indices) {
                                var data: ByteArray? = null
                                while (data == null && t.status == Status.RUNNING) {
                                    data = pending.remove(k)
                                    if (data == null) try { Thread.sleep(50) } catch (_: InterruptedException) { return@submit }
                                }
                                if (data == null) return@submit // paused/failed
                                ch.position(ch.size())
                                ch.write(java.nio.ByteBuffer.wrap(data))
                                offset += data.size
                                t.downloaded += data.size
                                t.hls?.segDone = skip + k + 1
                            }
                            ch.force(true)
                            if (t.status == Status.RUNNING) finalize(t, true)
                        } finally {
                            try { ch.close() } catch (_: Exception) {}
                            try { pfd.close() } catch (_: Exception) {}
                        }
                    } catch (e: Exception) {
                        if (t.status == Status.RUNNING) {
                            t.status = Status.FAILED; t.error = e.message ?: "HLS write failed"
                            persist(); notifyListeners(); ensureServiceStop()
                        }
                    }
                })

                // parallel segment workers
                val n = t.threads.coerceIn(1, 6)
                repeat(n) {
                    t.futures.add(exec.submit {
                        while (t.status == Status.RUNNING) {
                            val idx = nextIdx.getAndIncrement()
                            if (idx >= remain.size) return@submit
                            while (pending.size >= n * 2 + 2 && t.status == Status.RUNNING) {
                                try { Thread.sleep(50) } catch (_: InterruptedException) { return@submit }
                            }
                            if (t.status != Status.RUNNING) return@submit
                            val segUrl = remain[idx]
                            var data: ByteArray? = null
                            var err: Exception? = null
                            for (attempt in 1..4) {
                                if (t.status != Status.RUNNING) return@submit
                                try {
                                    val got = hlsFetchBytes(t, segUrl)
                                    if (got != null && got.isNotEmpty()) { data = got; break }
                                    err = IOException("Empty segment")
                                } catch (e: InterruptedException) { return@submit
                                } catch (e: Exception) { err = e }
                                try { Thread.sleep(700L * attempt) } catch (_: InterruptedException) { return@submit }
                            }
                            if (data == null || data!!.isEmpty()) {
                                if (t.status == Status.RUNNING) {
                                    t.status = Status.FAILED
                                    t.error = "Segment failed: ${err?.message ?: segUrl.takeLast(60)}"
                                    persist(); notifyListeners(); ensureServiceStop()
                                }
                                return@submit
                            }
                            var seg = data!!
                            // AES-128 decryption
                            if (key != null && key.size == 16) {
                                try {
                                    val iv = if (keyIv.length == 32) hexBytes(keyIv)
                                    else java.nio.ByteBuffer.allocate(16).apply {
                                        putLong(0); putLong((skip + idx).toLong())
                                    }.array()
                                    val c = Cipher.getInstance("AES/CBC/NoPadding")
                                    c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                                    seg = c.doFinal(seg)
                                } catch (e: Exception) {
                                    // keep raw segment if decryption fails
                                }
                            }
                            pending[idx] = seg
                        }
                    })
                }
            } catch (e: Exception) {
                if (t.isActive()) {
                    t.status = Status.FAILED
                    t.error = e.message ?: "HLS failed"
                    persist()
                    notifyListeners()
                    ensureServiceStop()
                }
            }
        }
    }

    private fun hexBytes(s: String): ByteArray {
        val len = s.length
        val out = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            out[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
        }
        return out
    }

    @Synchronized
    private fun finalize(t: Task, fromWorker: Boolean) {
        if (t.status == Status.COMPLETED) return
        t.downloaded = t.parts.sumOf { it.downloaded }
        if (t.total < 0) t.total = t.downloaded
        t.finishedAt = System.currentTimeMillis()
        t.speed = 0
        try {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
                put(MediaStore.Downloads.SIZE, t.downloaded)
            }
            t.uri?.let { appCtx.contentResolver.update(it, cv, null, null) }
        } catch (_: Exception) {
        }
        t.status = Status.COMPLETED
        persist()
        notifyListeners()
        completeNotification(t)
        ensureServiceStop()
    }

    // ---------------------------------------------------------------- ticker / persistence

    private fun tick() {
        try {
            val active = synchronized(tasks) { tasks.filter { it.status == Status.RUNNING } }
            for (t in active) {
                val now = if (t.hls != null) t.downloaded else t.parts.sumOf { it.downloaded }
                t.downloaded = now
                t.speed = ((now - t.lastTickBytes) * 2)  // bytes/sec (tick = 500ms)
                t.lastTickBytes = now
            }
            if (active.isNotEmpty()) {
                notifyListeners()
                updateSummaryNotification(active)
                dirty = true
            }
            if (dirty) {
                dirty = false
                persist()
            }
        } catch (_: Exception) {
        }
    }

    private fun persist() {
        dirty = true
        io.execute {
            val a = JSONArray()
            synchronized(tasks) {
                for (t in tasks) {
                    val parts = JSONArray()
                    synchronized(t.parts) {
                        for (p in t.parts) {
                            parts.put(JSONObject()
                                .put("s", p.start).put("e", p.end).put("d", p.downloaded))
                        }
                    }
                    a.put(JSONObject()
                        .put("id", t.id).put("url", t.url).put("name", t.name)
                        .put("mime", t.mime)
                        .put("ua", t.headers["User-Agent"] ?: "")
                        .put("cookie", t.headers["Cookie"] ?: "")
                        .put("total", t.total).put("range", t.supportsRange)
                        .put("uri", t.uri?.toString() ?: "")
                        .put("threads", t.threads).put("status", t.status.name)
                        .put("error", t.error ?: "")
                        .put("added", t.addedAt).put("finished", t.finishedAt)
                        .put("hls", t.hls != null)
                        .apply {
                            t.hls?.let {
                                put("hlsVariant", it.variantUrl)
                                put("segDone", it.segDone)
                                put("segTotal", it.segTotal)
                            }
                        }
                        .put("parts", parts))
                }
            }
            prefs.edit().putString("tasks", a.toString()).apply()
        }
    }

    private fun load() {
        try {
            val a = JSONArray(prefs.getString("tasks", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val t = Task(
                    o.getLong("id"), o.getString("url"), o.getString("name"), o.getString("mime"),
                    mapOf(
                        "User-Agent" to o.optString("ua", ""),
                        "Cookie" to o.optString("cookie", "")
                    ).filterValues { it.isNotBlank() },
                    o.optInt("threads", 4)
                )
                t.total = o.optLong("total", -1)
                t.supportsRange = o.optBoolean("range", false)
                val us = o.optString("uri", "")
                if (us.isNotBlank()) t.uri = Uri.parse(us)
                t.status = try { Status.valueOf(o.optString("status", "PAUSED")) } catch (_: Exception) { Status.PAUSED }
                t.error = o.optString("error", "").takeIf { it.isNotBlank() }
                t.addedAt = o.optLong("added", System.currentTimeMillis())
                t.finishedAt = o.optLong("finished", 0)
                if (o.optBoolean("hls", false)) {
                    t.hls = HlsInfo(
                        variantUrl = o.optString("hlsVariant", ""),
                        segDone = o.optInt("segDone", 0),
                        segTotal = o.optInt("segTotal", 0)
                    )
                }
                val ps = o.optJSONArray("parts") ?: JSONArray()
                for (j in 0 until ps.length()) {
                    val po = ps.getJSONObject(j)
                    val p = Part(po.getLong("s"), po.getLong("e"))
                    p.downloaded = po.getLong("d")
                    t.parts.add(p)
                }
                t.downloaded = t.parts.sumOf { it.downloaded }
                // tasks that were running when we died show as paused until resumed
                if (t.status == Status.RUNNING || t.status == Status.QUEUED) t.status = Status.PAUSED
                synchronized(tasks) { tasks.add(t) }
            }
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- listeners

    fun addListener(l: Listener) { listeners.add(l); l.onChanged(list()) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    private fun notifyListeners() {
        val snap = list()
        main.post { listeners.forEach { it.onChanged(snap) } }
    }

    // ---------------------------------------------------------------- service + notifications

    fun attachService(s: DownloadService) {
        service = s
        val pending = tasks.filter { it.status == Status.RUNNING || it.status == Status.QUEUED }
        if (pending.isNotEmpty()) {
            pending.forEach { resumeInternal(it) }
            ensureService()
        } else {
            s.stopSelf()
        }
    }

    fun detachService() { service = null }

    private fun ensureService() {
        val ctx = appCtx
        try {
            androidx.core.content.ContextCompat.startForegroundService(
                ctx, Intent(ctx, DownloadService::class.java))
        } catch (_: Exception) {
        }
    }

    private fun ensureServiceStop() {
        val anyActive = synchronized(tasks) { tasks.any { it.isActive() } }
        if (!anyActive) service?.stopSelf()
    }

    fun summaryNotification(): Notification {
        val active = list().filter { it.isActive() }
        return buildSummary(active)
    }

    private fun buildSummary(active: List<Task>): Notification {
        val b = NotificationCompat.Builder(appCtx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openDownloadsIntent())
        if (active.isEmpty()) {
            b.setContentTitle("Downloads")
                .setContentText("No active downloads")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
        } else if (active.size == 1) {
            val t = active[0]
            b.setContentTitle(t.name)
                .setContentText(
                    if (t.total > 0) "${t.progressPct()}% · ${fmt(t.downloaded)} / ${fmt(t.total)}"
                    else "${fmt(t.downloaded)} · ${t.threads} threads")
                .setProgress(100, t.progressPct(), t.total <= 0)
        } else {
            val totalBytes = active.sumOf { if (it.total > 0) it.total else 0 }
            val got = active.sumOf { it.downloaded }
            val pct = if (totalBytes > 0) ((got * 100 / totalBytes).toInt()) else 0
            b.setContentTitle("${active.size} downloads running")
                .setContentText("$pct% · ${fmt(got)} / ${fmt(totalBytes)}")
                .setProgress(100, pct, totalBytes <= 0)
        }
        return b.build()
    }

    private fun updateSummaryNotification(active: List<Task>) {
        try {
            val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(SUMMARY_NOTIF, buildSummary(active))
        } catch (_: Exception) {
        }
    }

    private fun completeNotification(t: Task) {
        try {
            val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val b = NotificationCompat.Builder(appCtx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Download complete")
                .setContentText(t.name)
                .setAutoCancel(true)
                .setContentIntent(openDownloadsIntent())
            openIntent(t)?.let { b.setContentIntent(PendingIntent.getActivity(
                appCtx, (t.id % 60000).toInt(), it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)) }
            nm.notify((t.id % 60000).toInt() + 70000, b.build())
        } catch (_: Exception) {
        }
    }

    private fun openDownloadsIntent(): PendingIntent = PendingIntent.getActivity(
        appCtx, 11,
        Intent(appCtx, DownloadsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    // ---------------------------------------------------------------- formatting

    fun fmt(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format("%.1f MB", mb)
        return String.format("%.2f GB", mb / 1024.0)
    }

    fun fmtSpeed(bytesPerSec: Long): String = if (bytesPerSec <= 0) "" else "${fmt(bytesPerSec)}/s"
}
