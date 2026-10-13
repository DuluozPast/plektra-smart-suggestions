package rocks.pkb.plektra.core.suggestions

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class CompactDictionaryV31Reader(
    file: File
) : Closeable {

    data class WordEntry(
        val word: String,
        val normalized: String,
        val frequency: Int
    )

    companion object {
        private const val HEADER_SIZE = 100
        private const val MAGIC = "P31D"
        private const val FORMAT_VERSION = 31

        private const val WORD_OFFSET_SIZE = 4
        private const val KEY_OFFSET_SIZE = 4
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

    val wordOffsetsOffset: Long
    val wordsDataOffset: Long

    val normalizedOffsetsOffset: Long
    val normalizedDataOffset: Long

    val prefixOffsetsOffset: Long
    val prefixDataOffset: Long

    val symDeleteOffsetsOffset: Long
    val symDeleteDataOffset: Long

    val declaredFileSize: Long

    init {
        require(channel.size() >= HEADER_SIZE) {
            "V3.1 dictionary is too small: ${channel.size()} bytes"
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
            "Invalid V3.1 dictionary magic: $magic"
        }

        formatVersion = header.int
        normalizationVersion = header.int

        wordCount = header.int
        normalizedCount = header.int
        prefixCount = header.int
        symDeleteCount = header.int

        wordOffsetsOffset = header.long
        wordsDataOffset = header.long

        normalizedOffsetsOffset = header.long
        normalizedDataOffset = header.long

        prefixOffsetsOffset = header.long
        prefixDataOffset = header.long

        symDeleteOffsetsOffset = header.long
        symDeleteDataOffset = header.long

        declaredFileSize = header.long

        require(formatVersion == FORMAT_VERSION) {
            "Unsupported dictionary format: $formatVersion"
        }

        require(declaredFileSize == channel.size()) {
            "V3.1 file size mismatch: " +
                    "header=$declaredFileSize actual=${channel.size()}"
        }

        require(
            wordOffsetsOffset == HEADER_SIZE.toLong() &&
                    wordOffsetsOffset < wordsDataOffset &&
                    wordsDataOffset < normalizedOffsetsOffset &&
                    normalizedOffsetsOffset < normalizedDataOffset &&
                    normalizedDataOffset < prefixOffsetsOffset &&
                    prefixOffsetsOffset < prefixDataOffset &&
                    prefixDataOffset < symDeleteOffsetsOffset &&
                    symDeleteOffsetsOffset < symDeleteDataOffset &&
                    symDeleteDataOffset < declaredFileSize
        ) {
            "Invalid V3.1 section offsets"
        }

        require(
            wordsDataOffset - wordOffsetsOffset ==
                    wordCount.toLong() * WORD_OFFSET_SIZE
        ) {
            "Invalid V3.1 word offset table size"
        }

        require(
            normalizedDataOffset - normalizedOffsetsOffset ==
                    normalizedCount.toLong() * KEY_OFFSET_SIZE
        ) {
            "Invalid V3.1 normalized offset table size"
        }

        require(
            prefixDataOffset - prefixOffsetsOffset ==
                    prefixCount.toLong() * KEY_OFFSET_SIZE
        ) {
            "Invalid V3.1 prefix offset table size"
        }

        require(
            symDeleteDataOffset - symDeleteOffsetsOffset ==
                    symDeleteCount.toLong() * KEY_OFFSET_SIZE
        ) {
            "Invalid V3.1 SymSpell offset table size"
        }
    }

    fun mappedSizeBytes(): Long =
        channel.size()

    fun wordEntryById(wordId: Int): WordEntry {
        require(wordId in 0 until wordCount)

        val relativeOffset =
            readUInt32(wordOffsetsOffset + wordId.toLong() * 4L)

        val recordPosition = wordsDataOffset + relativeOffset

        val reader = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        reader.position(recordPosition.toInt())

        val word = readUtf8(reader)
        val normalized = readUtf8(reader)
        val frequency = reader.int

        return WordEntry(
            word = word,
            normalized = normalized,
            frequency = frequency
        )
    }

    fun wordById(wordId: Int): String {
        return wordEntryById(wordId).word
    }

    fun normalizedWords(normalized: String): List<String> {
        var low = 0
        var high = normalizedCount - 1

        while (low <= high) {
            val mid = (low + high) ushr 1

            val recordPosition =
                normalizedDataOffset +
                        readUInt32(normalizedOffsetsOffset + mid.toLong() * 4L)

            val reader = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            reader.position(recordPosition.toInt())

            val key = readUtf8(reader)

            when {
                key < normalized -> low = mid + 1
                key > normalized -> high = mid - 1
                else -> {
                    val idCount = reader.int
                    require(idCount >= 0)

                    return List(idCount) {
                        val wordId = reader.int
                        wordById(wordId)
                    }
                }
            }
        }

        return emptyList()
    }

    fun normalizedEntries(normalized: String): List<WordEntry> {
        var low = 0
        var high = normalizedCount - 1

        while (low <= high) {
            val mid = (low + high) ushr 1

            val recordPosition =
                normalizedDataOffset +
                        readUInt32(normalizedOffsetsOffset + mid.toLong() * 4L)

            val reader = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            reader.position(recordPosition.toInt())

            val key = readUtf8(reader)

            when {
                key < normalized -> low = mid + 1
                key > normalized -> high = mid - 1
                else -> {
                    val idCount = reader.int
                    require(idCount >= 0)

                    return List(idCount) {
                        wordEntryById(reader.int)
                    }
                }
            }
        }

        return emptyList()
    }

    fun symDeleteWords(deleteKey: String): List<String> {
        var low = 0
        var high = symDeleteCount - 1

        while (low <= high) {
            val mid = (low + high) ushr 1

            val recordPosition =
                symDeleteDataOffset +
                        readUInt32(symDeleteOffsetsOffset + mid.toLong() * 4L)

            val reader = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            reader.position(recordPosition.toInt())

            val key = readUtf8(reader)

            when {
                key < deleteKey -> low = mid + 1
                key > deleteKey -> high = mid - 1
                else -> {
                    val idCount = reader.int
                    require(idCount >= 0)

                    return List(idCount) {
                        val wordId = reader.int
                        wordById(wordId)
                    }
                }
            }
        }

        return emptyList()
    }

    fun symDeleteEntries(deleteKey: String): List<WordEntry> {
        var low = 0
        var high = symDeleteCount - 1

        while (low <= high) {
            val mid = (low + high) ushr 1

            val recordPosition =
                symDeleteDataOffset +
                        readUInt32(
                            symDeleteOffsetsOffset + mid.toLong() * 4L
                        )

            val reader = buffer.duplicate()
                .order(ByteOrder.LITTLE_ENDIAN)

            reader.position(recordPosition.toInt())

            val key = readUtf8(reader)

            when {
                key < deleteKey -> low = mid + 1
                key > deleteKey -> high = mid - 1
                else -> {
                    val idCount = reader.int
                    require(idCount >= 0)

                    return List(idCount) {
                        wordEntryById(reader.int)
                    }
                }
            }
        }

        return emptyList()
    }

    fun prefixWords(prefix: String): List<String> {
        var low = 0
        var high = prefixCount - 1

        while (low <= high) {
            val mid = (low + high) ushr 1

            val recordPosition =
                prefixDataOffset +
                        readUInt32(
                            prefixOffsetsOffset + mid.toLong() * 4L
                        )

            val reader = buffer.duplicate()
                .order(ByteOrder.LITTLE_ENDIAN)

            reader.position(recordPosition.toInt())

            val key = readUtf8(reader)

            when {
                key < prefix -> {
                    low = mid + 1
                }

                key > prefix -> {
                    high = mid - 1
                }

                else -> {
                    val idCount = reader.int

                    require(idCount >= 0) {
                        "Invalid prefix ID count for '$prefix': $idCount"
                    }

                    return List(idCount) {
                        val wordId = reader.int
                        wordById(wordId)
                    }
                }
            }
        }

        return emptyList()
    }

    fun prefixEntries(prefix: String): List<WordEntry> {
        var low = 0
        var high = prefixCount - 1

        while (low <= high) {
            val mid = (low + high) ushr 1

            val recordPosition =
                prefixDataOffset +
                        readUInt32(prefixOffsetsOffset + mid.toLong() * 4L)

            val reader = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            reader.position(recordPosition.toInt())

            val key = readUtf8(reader)

            when {
                key < prefix -> low = mid + 1
                key > prefix -> high = mid - 1
                else -> {
                    val idCount = reader.int
                    require(idCount >= 0)

                    return List(idCount) {
                        wordEntryById(reader.int)
                    }
                }
            }
        }

        return emptyList()
    }

    fun prefixEntries(
        prefix: String,
        limit: Int
    ): List<WordEntry> {
        if (limit <= 0) {
            return emptyList()
        }

        var low = 0
        var high = prefixCount - 1

        while (low <= high) {
            val mid = (low + high) ushr 1

            val recordPosition =
                prefixDataOffset +
                        readUInt32(
                            prefixOffsetsOffset + mid.toLong() * 4L
                        )

            val reader = buffer.duplicate()
                .order(ByteOrder.LITTLE_ENDIAN)

            reader.position(recordPosition.toInt())

            val key = readUtf8(reader)

            when {
                key < prefix -> {
                    low = mid + 1
                }

                key > prefix -> {
                    high = mid - 1
                }

                else -> {
                    val idCount = reader.int

                    require(idCount >= 0) {
                        "Invalid prefix ID count for '$prefix': $idCount"
                    }

                    val resultCount =
                        minOf(idCount, limit)

                    return List(resultCount) {
                        wordEntryById(reader.int)
                    }
                }
            }
        }

        return emptyList()
    }

    private fun readUInt32(position: Long): Long {
        require(position >= 0L && position + 4L <= declaredFileSize) {
            "Invalid uint32 position: $position"
        }

        val reader = buffer.duplicate()
            .order(ByteOrder.LITTLE_ENDIAN)

        reader.position(position.toInt())

        return reader.int.toLong() and 0xFFFFFFFFL
    }

    private fun readUtf8(reader: java.nio.ByteBuffer): String {
        val length = reader.short.toInt() and 0xFFFF

        val bytes = ByteArray(length)
        reader.get(bytes)

        return String(bytes, Charsets.UTF_8)
    }

    override fun close() {
        channel.close()
        randomAccessFile.close()
    }
}