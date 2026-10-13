package rocks.pkb.plektra.core.suggestions

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class CompactDictionaryV3Reader(
    file: File
) : Closeable {

    companion object {
        private const val HEADER_SIZE = 68
        private const val MAGIC = "PST3"
    }

    private val randomAccessFile =
        RandomAccessFile(file, "r")

    private val channel: FileChannel =
        randomAccessFile.channel

    private val buffer: MappedByteBuffer =
        channel.map(
            FileChannel.MapMode.READ_ONLY,
            0L,
            channel.size()
        ).apply {
            order(ByteOrder.LITTLE_ENDIAN)
        }

    val formatVersion: Int
    val normalizationVersion: Int

    val wordCount: Int
    val normalizedCount: Int
    val prefixCount: Int
    val symDeleteCount: Int

    val wordsOffset: Long
    val normalizedOffset: Long
    val prefixOffset: Long
    val symDeleteOffset: Long

    val declaredFileSize: Long

    init {
        require(channel.size() >= HEADER_SIZE) {
            "V3 dictionary is too small: ${channel.size()} bytes"
        }

        val header = buffer.duplicate()
            .order(ByteOrder.LITTLE_ENDIAN)

        header.position(0)

        val magicBytes = ByteArray(4)
        header.get(magicBytes)

        val magic = String(
            magicBytes,
            Charsets.US_ASCII
        )

        require(magic == MAGIC) {
            "Invalid V3 dictionary magic: $magic"
        }

        formatVersion = header.int
        normalizationVersion = header.int

        wordCount = header.int
        normalizedCount = header.int
        prefixCount = header.int
        symDeleteCount = header.int

        wordsOffset = header.long
        normalizedOffset = header.long
        prefixOffset = header.long
        symDeleteOffset = header.long

        declaredFileSize = header.long

        require(formatVersion == 3) {
            "Unsupported dictionary format: $formatVersion"
        }

        require(declaredFileSize == channel.size()) {
            "V3 file size mismatch: " +
                    "header=$declaredFileSize actual=${channel.size()}"
        }

        require(
            wordsOffset == HEADER_SIZE.toLong() &&
                    wordsOffset < normalizedOffset &&
                    normalizedOffset < prefixOffset &&
                    prefixOffset < symDeleteOffset &&
                    symDeleteOffset < declaredFileSize
        ) {
            "Invalid V3 section offsets"
        }
    }

    fun mappedSizeBytes(): Long =
        channel.size()

    override fun close() {
        channel.close()
        randomAccessFile.close()
    }
}