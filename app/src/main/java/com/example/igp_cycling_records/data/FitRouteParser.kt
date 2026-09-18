package com.example.igp_cycling_heatmap.data

import android.util.Log

data class FitPoint(
    val lat: Double,
    val lng: Double,
)

object FitRouteParser {
    private const val TAG = "FitRouteParser"
    private const val GLOBAL_RECORD_MESSAGE = 20
    private const val FIELD_LAT = 0
    private const val FIELD_LNG = 1
    private const val INVALID = Int.MAX_VALUE

    private data class FieldDef(
        val number: Int,
        val size: Int,
        val offset: Int,
    )

    private data class Definition(
        val littleEndian: Boolean,
        val fields: List<FieldDef>,
        val size: Int,
        val timestampSize: Int,
    )

    fun parse(bytes: ByteArray): List<FitPoint> {
        return try {
            val data = bytes
            if (data.size < 14) return emptyList()
            val headerSize = data[0].toInt() and 0xFF
            if (headerSize != 12 && headerSize != 14) return emptyList()
            val signature = String(
                byteArrayOf(data[8], data[9], data[10], data[11]),
                Charsets.US_ASCII,
            )
            if (signature != ".FIT") return emptyList()

            val dataSize = readU32(data, 4)
            val dataEnd = minOf(headerSize + dataSize, data.size - 2)
            val definitions = mutableMapOf<Int, Definition>()
            val points = mutableListOf<FitPoint>()
            var offset = headerSize

            while (offset < dataEnd) {
                val recordHeader = data[offset].toInt() and 0xFF
                offset += 1

                if (recordHeader and 0x80 != 0) {
                    val localType = (recordHeader shr 5) and 0x03
                    val definition = definitions[localType]
                    if (definition == null) {
                        offset += 4
                        continue
                    }
                    parseRecord(data, offset, definition, compressed = true)?.let { points += it }
                    offset += definition.size - definition.timestampSize
                } else {
                    val localType = recordHeader and 0x0F
                    val isDefinition = recordHeader and 0x40 != 0
                    val hasDeveloperData = recordHeader and 0x20 != 0
                    if (isDefinition) {
                        val parsed = parseDefinition(data, offset, hasDeveloperData)
                        definitions[localType] = parsed.first
                        offset = parsed.second
                    } else {
                        val definition = definitions[localType]
                        if (definition == null) continue
                        parseRecord(data, offset, definition, compressed = false)?.let { points += it }
                        offset += definition.size
                    }
                }
            }
            points
        } catch (e: Exception) {
            Log.w(TAG, "FIT 解析失败: ${e.message}")
            emptyList()
        }
    }

    private fun parseDefinition(
        data: ByteArray,
        startOffset: Int,
        hasDeveloperData: Boolean,
    ): Pair<Definition, Int> {
        var offset = startOffset + 1
        val architecture = data[offset].toInt() and 0xFF
        offset += 1
        val littleEndian = architecture == 0
        val globalMessage = if (littleEndian) readU16(data, offset) else readU16Big(data, offset)
        offset += 2
        val fieldCount = data[offset].toInt() and 0xFF
        offset += 1
        val fields = mutableListOf<FieldDef>()
        var messageOffset = 0
        for (i in 0 until fieldCount) {
            val number = data[offset].toInt() and 0xFF
            val size = data[offset + 1].toInt() and 0xFF
            offset += 3
            fields += FieldDef(number, size, messageOffset)
            messageOffset += size
        }
        if (hasDeveloperData) {
            val developerCount = data[offset].toInt() and 0xFF
            offset += 1
            for (i in 0 until developerCount) {
                val size = data[offset + 1].toInt() and 0xFF
                offset += 3
                messageOffset += size
            }
        }
        // 只保留 record 消息定义，避免无意义解析。
        val usable = if (globalMessage == GLOBAL_RECORD_MESSAGE) {
            val timestampSize = fields.firstOrNull { it.number == 253 }?.size ?: 0
            Definition(littleEndian, fields, messageOffset, timestampSize)
        } else {
            Definition(littleEndian, emptyList(), messageOffset, 0)
        }
        return usable to offset
    }

    private fun parseRecord(
        data: ByteArray,
        dataOffset: Int,
        definition: Definition,
        compressed: Boolean,
    ): FitPoint? {
        if (definition.fields.isEmpty()) return null
        val latField = definition.fields.firstOrNull { it.number == FIELD_LAT } ?: return null
        val lngField = definition.fields.firstOrNull { it.number == FIELD_LNG } ?: return null
        if (latField.size != 4 || lngField.size != 4) return null
        val timestampOffset = definition.fields
            .firstOrNull { it.number == 253 }
            ?.offset
            ?: 0

        fun fieldOffset(field: FieldDef): Int {
            return if (compressed && field.offset > timestampOffset) {
                field.offset - definition.timestampSize
            } else {
                field.offset
            }
        }

        val latRaw = readI32(
            data,
            dataOffset + fieldOffset(latField),
            definition.littleEndian,
        )
        val lngRaw = readI32(
            data,
            dataOffset + fieldOffset(lngField),
            definition.littleEndian,
        )
        if (latRaw == INVALID || lngRaw == INVALID || latRaw == Int.MIN_VALUE ||
            lngRaw == Int.MIN_VALUE
        ) return null

        val lat = semicirclesToDegrees(latRaw)
        val lng = semicirclesToDegrees(lngRaw)
        if (!lat.isFinite() || !lng.isFinite()) return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        if (lat == 0.0 && lng == 0.0) return null
        return FitPoint(lat, lng)
    }

    private fun readU32(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun readU16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun readU16Big(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun readI32(data: ByteArray, offset: Int, littleEndian: Boolean): Int {
        return if (littleEndian) {
            (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16) or
                ((data[offset + 3].toInt() and 0xFF) shl 24)
        } else {
            ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
        }
    }

    private fun semicirclesToDegrees(value: Int): Double =
        value * (180.0 / 2147483648.0)
}
