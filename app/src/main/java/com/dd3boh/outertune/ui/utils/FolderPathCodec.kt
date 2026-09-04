package com.dd3boh.outertune.ui.utils

private const val ENCODED_FOLDER_PATH_PREFIX = "v2_"
private const val HEX_DIGITS = "0123456789abcdef"

internal fun decodeFolderPathArgument(argument: String?): String {
    if (argument == null) return STORAGE_ROOT
    if (!argument.startsWith(ENCODED_FOLDER_PATH_PREFIX)) {
        return argument.replace(';', '/')
    }

    val encodedPath = argument.removePrefix(ENCODED_FOLDER_PATH_PREFIX)
    return decodeHexPath(encodedPath)
        ?.takeIf { path -> path.startsWith(STORAGE_ROOT) }
        ?: STORAGE_ROOT
}

internal fun encodeFolderPathArgument(path: String): String = buildString {
    append(ENCODED_FOLDER_PATH_PREFIX)
    path.encodeToByteArray().forEach { byte ->
        val value = byte.toInt() and 0xff
        append(HEX_DIGITS[value ushr 4])
        append(HEX_DIGITS[value and 0x0f])
    }
}

private fun decodeHexPath(value: String): String? {
    if (value.length % 2 != 0) return null

    val bytes = ByteArray(value.length / 2)
    for (index in bytes.indices) {
        val high = value[index * 2].digitToIntOrNull(16) ?: return null
        val low = value[index * 2 + 1].digitToIntOrNull(16) ?: return null
        bytes[index] = ((high shl 4) or low).toByte()
    }

    return runCatching {
        bytes.decodeToString(throwOnInvalidSequence = true)
    }.getOrNull()
}
