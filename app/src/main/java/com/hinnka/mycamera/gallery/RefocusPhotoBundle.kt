package com.hinnka.mycamera.gallery

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Photon-only payload stored after the JPEG EOI marker. The image remains one ordinary JPEG
 * to MediaStore and other gallery apps; Photon can find and extract its hidden refocus bundle.
 */
internal object RefocusPhotoBundle {
    private val MAGIC = "PHOTONRF".toByteArray(Charsets.US_ASCII)
    private const val TRAILER_SIZE = 12
    private const val MAX_UNCOMPRESSED_BYTES = 256L * 1024L * 1024L
    private val FRAME_NAME = Regex("frame_\\d{2,3}\\.jpg")

    data class Extracted(
        val frames: List<File>,
        val depthMap: File?,
        val coverIndex: Int,
    )

    private data class PayloadRange(val start: Long, val length: Long)

    fun createArchive(
        frames: List<File>,
        depthMap: File?,
        coverIndex: Int,
        destination: File,
    ): Boolean {
        val validFrames = frames.filter { it.isFile && it.length() > 0L }.sortedBy { it.name }
        if (validFrames.size < 3) return false
        return try {
            destination.parentFile?.mkdirs()
            ZipOutputStream(destination.outputStream().buffered()).use { zip ->
                // JPEG and PNG are already compressed, so avoid wasting CPU recompressing them.
                zip.setLevel(Deflater.NO_COMPRESSION)
                val selectedCover = coverIndex.coerceIn(0, validFrames.lastIndex)
                val manifest = "version=1\nframe_count=\${validFrames.size}\ncover_index=\${selectedCover}\n"
                zip.putNextEntry(ZipEntry("refocus/manifest.txt"))
                zip.write(manifest.toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                validFrames.forEachIndexed { index, file ->
                    zip.putNextEntry(ZipEntry("refocus/frame_\${index.toString().padStart(2, '0')}.jpg"))
                    FileInputStream(file).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                if (depthMap?.isFile == true && depthMap.length() > 0L) {
                    zip.putNextEntry(ZipEntry("refocus/depth.png"))
                    FileInputStream(depthMap).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            destination.isFile && destination.length() > 0L
        } catch (_: Exception) {
            destination.delete()
            false
        }
    }

    fun appendArchive(jpeg: File, archive: File): Boolean {
        if (!jpeg.isFile || jpeg.length() <= 0L || !archive.isFile ||
            archive.length() <= 0L || archive.length() > Int.MAX_VALUE
        ) return false
        if (findPayload(jpeg) != null) return true

        val parent = jpeg.parentFile ?: return false
        val temp = runCatching { File.createTempFile("refocus_jpeg_", ".tmp", parent) }.getOrNull()
            ?: return false
        return try {
            FileOutputStream(temp).use { output ->
                FileInputStream(jpeg).use { it.copyTo(output) }
                FileInputStream(archive).use { it.copyTo(output) }
                output.write(
                    ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(archive.length().toInt()).array()
                )
                output.write(MAGIC)
                output.flush()
            }
            replaceFile(jpeg, temp)
        } catch (_: Exception) {
            temp.delete()
            false
        }
    }

    fun copyArchive(file: File, destination: File): Boolean {
        val range = findPayload(file) ?: return false
        return try {
            destination.parentFile?.mkdirs()
            RandomAccessFile(file, "r").use { input ->
                FileOutputStream(destination).use { output ->
                    input.seek(range.start)
                    val buffer = ByteArray(64 * 1024)
                    var remaining = range.length
                    while (remaining > 0L) {
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (count < 0) throw IOException("Unexpected end of embedded refocus ZIP")
                        output.write(buffer, 0, count)
                        remaining -= count
                    }
                }
            }
            destination.isFile && destination.length() == range.length
        } catch (_: Exception) {
            destination.delete()
            false
        }
    }

    fun extract(file: File, outputDir: File): Extracted? {
        val range = findPayload(file) ?: return null
        if (outputDir.exists() && !outputDir.deleteRecursively()) return null
        if (!outputDir.mkdirs() && !outputDir.isDirectory) return null
        val archive = runCatching { File.createTempFile("refocus_", ".zip", outputDir) }.getOrNull()
            ?: return null

        return try {
            RandomAccessFile(file, "r").use { input ->
                FileOutputStream(archive).use { output ->
                    input.seek(range.start)
                    val buffer = ByteArray(64 * 1024)
                    var remaining = range.length
                    while (remaining > 0L) {
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (count < 0) throw IOException("Truncated embedded refocus ZIP")
                        output.write(buffer, 0, count)
                        remaining -= count
                    }
                }
            }

            var coverIndex = -1
            var totalBytes = 0L
            val frames = mutableListOf<File>()
            var depthMap: File? = null
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val frameName = entry.name.substringAfterLast('/')
                    val isFrame = entry.name.startsWith("refocus/") && FRAME_NAME.matches(frameName)
                    val isDepth = entry.name == "refocus/depth.png"
                    val isManifest = entry.name == "refocus/manifest.txt"
                    if (isManifest) {
                        val bytes = readEntryBounded(zip, 16 * 1024)
                        val manifest = bytes.toString(Charsets.UTF_8)
                        coverIndex = manifest.lineSequence()
                            .firstOrNull { it.startsWith("cover_index=") }
                            ?.substringAfter('=')
                            ?.toIntOrNull() ?: coverIndex
                        totalBytes += bytes.size
                    } else if (isFrame || isDepth) {
                        val outputFile = File(outputDir, if (isDepth) "depth.png" else frameName)
                        FileOutputStream(outputFile).use { output ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                totalBytes += count
                                if (totalBytes > MAX_UNCOMPRESSED_BYTES) {
                                    throw IOException("Refocus payload exceeds extraction limit")
                                }
                                output.write(buffer, 0, count)
                            }
                        }
                        if (outputFile.length() > 0L) {
                            if (isDepth) depthMap = outputFile else frames.add(outputFile)
                        }
                    } else {
                        totalBytes += drainEntry(zip)
                        if (totalBytes > MAX_UNCOMPRESSED_BYTES) {
                            throw IOException("Refocus payload exceeds extraction limit")
                        }
                    }
                    zip.closeEntry()
                }
            }

            val ordered = frames.sortedBy { it.name }
            if (ordered.size < 3) {
                outputDir.deleteRecursively()
                null
            } else {
                val resolvedCover = if (coverIndex in ordered.indices) coverIndex else ordered.size / 2
                Extracted(ordered, depthMap, resolvedCover)
            }
        } catch (_: Exception) {
            outputDir.deleteRecursively()
            null
        } finally {
            archive.delete()
        }
    }

    private fun findPayload(file: File): PayloadRange? {
        if (!file.isFile || file.length() < TRAILER_SIZE.toLong()) return null
        return try {
            RandomAccessFile(file, "r").use { input ->
                input.seek(input.length() - TRAILER_SIZE)
                val trailer = ByteArray(TRAILER_SIZE)
                input.readFully(trailer)
                if (!trailer.copyOfRange(4, TRAILER_SIZE).contentEquals(MAGIC)) return null
                val length = ByteBuffer.wrap(trailer, 0, 4)
                    .order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                val start = input.length() - TRAILER_SIZE - length
                if (length <= 0L || start < 0L) return null
                input.seek(start)
                if (input.read() != 0x50 || input.read() != 0x4B) return null
                PayloadRange(start, length)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readEntryBounded(zip: ZipInputStream, maxBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (true) {
            val count = zip.read(buffer)
            if (count < 0) break
            if (output.size() + count > maxBytes) throw IOException("Oversized refocus manifest")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun drainEntry(zip: ZipInputStream): Long {
        val buffer = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val count = zip.read(buffer)
            if (count < 0) break
            total += count
        }
        return total
    }

    private fun replaceFile(target: File, temp: File): Boolean {
        val backup = File(target.parentFile, "\${target.name}.refocus_backup")
        if (backup.exists() && !backup.delete()) {
            temp.delete()
            return false
        }
        if (!target.renameTo(backup)) {
            temp.delete()
            return false
        }
        if (!temp.renameTo(target)) {
            backup.renameTo(target)
            temp.delete()
            return false
        }
        backup.delete()
        return true
    }
}
