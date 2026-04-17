package com.example.goprogps.parser

import android.media.MediaExtractor
import android.media.MediaFormat
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.example.goprogps.model.GpsPoint
import com.example.goprogps.model.GpsTrack
import java.io.FileDescriptor
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object GpmfParser {

    private const val TAG = "GpmfParser"

    fun parse(fd: FileDescriptor): GpsTrack? {
        val extractorResult = runCatching { parseViaExtractor(fd) }
        extractorResult.exceptionOrNull()?.let { Log.e(TAG, "MediaExtractor strategy failed", it) }
        extractorResult.getOrNull()?.let { return it }

        val boxResult = runCatching { parseViaBoxes { offset, size -> readFd(fd, offset, size) } }
        boxResult.exceptionOrNull()?.let { Log.e(TAG, "Box parser strategy failed", it) }
        return boxResult.getOrNull()
    }

    fun parse(path: String): GpsTrack? {
        val extractorResult = runCatching { parseViaExtractorPath(path) }
        extractorResult.exceptionOrNull()?.let { Log.e(TAG, "MediaExtractor strategy failed for path", it) }
        extractorResult.getOrNull()?.let { return it }

        val boxResult = runCatching {
            RandomAccessFile(path, "r").use { raf ->
                parseViaBoxes { offset, size ->
                    raf.seek(offset); ByteArray(size).also { raf.readFully(it) }
                }
            }
        }
        boxResult.exceptionOrNull()?.let { Log.e(TAG, "Box parser strategy failed for path", it) }
        return boxResult.getOrNull()
    }

    // ── MediaExtractor path ──────────────────────────────────────────────────

    private fun parseViaExtractor(fd: FileDescriptor): GpsTrack? {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(fd); extractAndParse(ex)
        } finally {
            ex.release()
        }
    }

    private fun parseViaExtractorPath(path: String): GpsTrack? {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(path); extractAndParse(ex)
        } finally {
            ex.release()
        }
    }

    private fun extractAndParse(ex: MediaExtractor): GpsTrack? {
        val idx = (0 until ex.trackCount).firstOrNull { i ->
            ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) == "application/octet-stream"
        } ?: run {
            Log.e(TAG, "No GPMF track (application/octet-stream) found among ${ex.trackCount} tracks")
            return null
        }
        ex.selectTrack(idx)

        val bufSize = ex.getTrackFormat(idx)
            .getInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024)
            .coerceAtLeast(2 * 1024 * 1024)
        val buf = ByteBuffer.allocate(bufSize)
        val points = mutableListOf<GpsPoint>()

        while (true) {
            buf.clear()
            val n = ex.readSampleData(buf, 0)
            if (n <= 0) break
            val timeMs = ex.sampleTime / 1000
            buf.rewind()
            val chunk = ByteArray(n).also { buf.get(it) }
            parseGpmfChunk(chunk, timeMs, points)
            ex.advance()
        }
        return if (points.isNotEmpty()) {
            Log.d(TAG, "MediaExtractor: extracted ${points.size} GPS points")
            GpsTrack(points)
        } else {
            Log.e(TAG, "GPMF track found but no GPS5 data extracted")
            null
        }
    }

    // ── Direct MP4 box parser fallback ───────────────────────────────────────

    private fun parseViaBoxes(read: (offset: Long, size: Int) -> ByteArray): GpsTrack? {
        // Locate moov box
        val fileSize = run {
            // Read enough to find ftyp/moov/mdat offsets
            Long.MAX_VALUE / 2
        }

        val moov = findBox(read, 0L, fileSize, "moov") ?: run {
            Log.e(TAG, "Box parser: moov box not found — not a valid MP4?")
            return null
        }
        val traks = findAllBoxes(read, moov.first, moov.second, "trak")
        Log.d(TAG, "Box parser: found ${traks.size} trak boxes")

        val gpmdTrak = traks.firstOrNull { trak ->
            val mdia = findBox(read, trak.first, trak.second, "mdia") ?: return@firstOrNull false
            val minf = findBox(read, mdia.first, mdia.second, "minf") ?: return@firstOrNull false
            val stbl = findBox(read, minf.first, minf.second, "stbl") ?: return@firstOrNull false
            val stsd = findBox(read, stbl.first, stbl.second, "stsd") ?: return@firstOrNull false
            // stsd: 4 ver/flags + 4 count + 4 entry-size, then 4-byte codec at offset 12
            runCatching { String(read(stsd.first + 12, 4)) == "gpmd" }.getOrDefault(false)
        } ?: run {
            Log.e(TAG, "Box parser: no GPMD (GoPro metadata) track found among ${traks.size} tracks")
            return null
        }

        val mdia = findBox(read, gpmdTrak.first, gpmdTrak.second, "mdia")!!
        val minf = findBox(read, mdia.first, mdia.second, "minf")!!
        val stbl = findBox(read, minf.first, minf.second, "stbl")!!

        val chunkOffsets = readChunkOffsets(read, stbl)
        val sampleSizes = readSampleSizes(read, stbl)
        if (chunkOffsets.isEmpty() || sampleSizes.isEmpty()) {
            Log.e(TAG, "Box parser: missing chunk offsets (${chunkOffsets.size}) or sample sizes (${sampleSizes.size})")
            return null
        }

        val sampleOffsets = resolveSampleOffsets(read, stbl, chunkOffsets, sampleSizes)

        val points = mutableListOf<GpsPoint>()
        for ((i, pair) in sampleOffsets.withIndex()) {
            val (offset, size) = pair
            runCatching {
                parseGpmfChunk(read(offset, size), i * 1000L, points)
            }
        }
        return if (points.isNotEmpty()) {
            Log.d(TAG, "Box parser: extracted ${points.size} GPS points from ${sampleOffsets.size} samples")
            GpsTrack(points)
        } else {
            Log.e(TAG, "Box parser: ${sampleOffsets.size} samples parsed but no valid GPS5 data found")
            null
        }
    }

    private fun findBox(
        read: (Long, Int) -> ByteArray,
        start: Long,
        end: Long,
        target: String
    ): Pair<Long, Long>? {
        var pos = start
        while (pos + 8 <= end) {
            val hdr = runCatching { read(pos, 8) }.getOrNull() ?: break
            val size = readU32BE(hdr, 0)
            val fcc = String(hdr, 4, 4)
            if (size < 8) break
            val boxEnd = pos + size
            if (fcc == target) return Pair(pos + 8, boxEnd)
            pos = boxEnd
        }
        return null
    }

    private fun findAllBoxes(
        read: (Long, Int) -> ByteArray,
        start: Long,
        end: Long,
        target: String
    ): List<Pair<Long, Long>> {
        val result = mutableListOf<Pair<Long, Long>>()
        var pos = start
        while (pos + 8 <= end) {
            val hdr = runCatching { read(pos, 8) }.getOrNull() ?: break
            val size = readU32BE(hdr, 0)
            val fcc = String(hdr, 4, 4)
            if (size < 8) break
            val boxEnd = pos + size
            if (fcc == target) result.add(Pair(pos + 8, boxEnd))
            pos = boxEnd
        }
        return result
    }

    private fun readChunkOffsets(read: (Long, Int) -> ByteArray, stbl: Pair<Long, Long>): LongArray {
        val co64 = findBox(read, stbl.first, stbl.second, "co64")
        if (co64 != null) {
            val d = read(co64.first, (co64.second - co64.first).toInt())
            val count = readI32BE(d, 4)
            return LongArray(count) { i -> readI64BE(d, 8 + i * 8) }
        }
        val stco = findBox(read, stbl.first, stbl.second, "stco") ?: return LongArray(0)
        val d = read(stco.first, (stco.second - stco.first).toInt())
        val count = readI32BE(d, 4)
        return LongArray(count) { i -> readU32BE(d, 8 + i * 4) }
    }

    private fun readSampleSizes(read: (Long, Int) -> ByteArray, stbl: Pair<Long, Long>): IntArray {
        val stsz = findBox(read, stbl.first, stbl.second, "stsz") ?: return IntArray(0)
        val d = read(stsz.first, (stsz.second - stsz.first).toInt())
        val default = readI32BE(d, 4)
        val count = readI32BE(d, 8)
        return if (default != 0) IntArray(count) { default }
        else IntArray(count) { i -> readI32BE(d, 12 + i * 4) }
    }

    private fun resolveSampleOffsets(
        read: (Long, Int) -> ByteArray,
        stbl: Pair<Long, Long>,
        chunkOffsets: LongArray,
        sampleSizes: IntArray
    ): List<Pair<Long, Int>> {
        data class StscEntry(val firstChunk: Int, val samplesPerChunk: Int)
        val entries = mutableListOf<StscEntry>()
        findBox(read, stbl.first, stbl.second, "stsc")?.let { stsc ->
            val d = read(stsc.first, (stsc.second - stsc.first).toInt())
            val count = readI32BE(d, 4)
            for (i in 0 until count) {
                entries.add(StscEntry(readI32BE(d, 8 + i * 12), readI32BE(d, 12 + i * 12)))
            }
        }

        val result = mutableListOf<Pair<Long, Int>>()
        var sampleIdx = 0
        for (chunkIdx in chunkOffsets.indices) {
            val chunkNum = chunkIdx + 1
            val spc = entries.lastOrNull { it.firstChunk <= chunkNum }?.samplesPerChunk ?: 1
            var offset = chunkOffsets[chunkIdx]
            repeat(spc) {
                if (sampleIdx < sampleSizes.size) {
                    result.add(Pair(offset, sampleSizes[sampleIdx]))
                    offset += sampleSizes[sampleIdx]
                    sampleIdx++
                }
            }
        }
        return result
    }

    private fun readFd(fd: FileDescriptor, offset: Long, size: Int): ByteArray {
        Os.lseek(fd, offset, OsConstants.SEEK_SET)
        val buf = ByteArray(size)
        var pos = 0
        while (pos < size) {
            val n = Os.read(fd, buf, pos, size - pos)
            if (n <= 0) break
            pos += n
        }
        return buf
    }

    // ── GPMF parsing ─────────────────────────────────────────────────────────

    private fun parseGpmfChunk(data: ByteArray, baseTimeMs: Long, out: MutableList<GpsPoint>) {
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        parseContainer(buf, data.size, baseTimeMs, LongArray(5) { 1L }, out)
    }

    private fun parseContainer(
        buf: ByteBuffer,
        length: Int,
        baseTimeMs: Long,
        scales: LongArray,
        out: MutableList<GpsPoint>
    ) {
        val end = buf.position() + length
        while (buf.position() + 8 <= end && buf.remaining() >= 8) {
            val key = String(ByteArray(4).also { buf.get(it) })
            val type = buf.get().toInt() and 0xFF
            val size = buf.get().toInt() and 0xFF
            val repeat = buf.short.toInt() and 0xFFFF
            val dataBytes = size * repeat
            val paddedBytes = (dataBytes + 3) and 3.inv()
            val dataStart = buf.position()
            if (dataStart + paddedBytes > buf.limit()) break

            when {
                type == 0 -> parseContainer(buf, dataBytes, baseTimeMs, LongArray(5) { 1L }, out)
                key == "SCAL" -> readScales(buf, type, repeat, scales)
                key == "GPS5" && type == 'l'.code && size == 20 -> readGps5(buf, repeat, baseTimeMs, scales, out)
            }

            buf.position(dataStart + paddedBytes)
        }
    }

    private fun readScales(buf: ByteBuffer, type: Int, repeat: Int, scales: LongArray) {
        val values = LongArray(repeat) {
            when (type) {
                'S'.code -> (buf.short.toInt() and 0xFFFF).toLong()
                'L'.code -> buf.int.toLong() and 0xFFFFFFFFL
                's'.code -> buf.short.toLong()
                'l'.code -> buf.int.toLong()
                else -> 1L
            }
        }
        when (values.size) {
            5 -> values.copyInto(scales)
            else -> scales.fill(if (values.isEmpty() || values[0] == 0L) 1L else values[0])
        }
    }

    private fun readGps5(
        buf: ByteBuffer, repeat: Int, baseTimeMs: Long, scales: LongArray, out: MutableList<GpsPoint>
    ) {
        val intervalMs = if (repeat > 1) 1000L / repeat else 55L
        repeat(repeat) { i ->
            val lat = buf.int.toDouble() / scales[0]
            val lon = buf.int.toDouble() / scales[1]
            val alt = buf.int.toDouble() / scales[2]
            val spd2 = buf.int.toDouble() / scales[3]
            val spd3 = buf.int.toDouble() / scales[4]
            if (lat.isFinite() && lon.isFinite()
                && (lat != 0.0 || lon != 0.0)
                && lat in -90.0..90.0 && lon in -180.0..180.0
            ) {
                out.add(GpsPoint(lat, lon, alt, spd2, spd3, baseTimeMs + i * intervalMs))
            }
        }
    }

    // ── Byte helpers ─────────────────────────────────────────────────────────

    private fun readI32BE(d: ByteArray, o: Int) =
        ((d[o].toInt() and 0xFF) shl 24) or ((d[o+1].toInt() and 0xFF) shl 16) or
        ((d[o+2].toInt() and 0xFF) shl 8) or (d[o+3].toInt() and 0xFF)

    private fun readU32BE(d: ByteArray, o: Int) = readI32BE(d, o).toLong() and 0xFFFFFFFFL

    private fun readI64BE(d: ByteArray, o: Int) =
        (readU32BE(d, o) shl 32) or readU32BE(d, o + 4)
}
