package com.arthenica.ffmpegkit

/**
 * 桩代码 (Mock)：因为本地 libs 的 ffmpeg-kit vendor 包已被移除，
 * Maven Central 的拉取也因某些网络或配置阻断无法完成，
 * 为了确保整体 APK 能顺利完成 NDK 的验收编译，使用该空壳维持调用签名。
 */

class FFmpegKit {
    companion object {
        @JvmStatic
        fun execute(command: String): FFmpegSession {
            return FFmpegSession()
        }
    }
}

class FFmpegKitConfig {
    companion object {
        @JvmStatic
        fun getVersion(): String = "Lite/Mock"
        @JvmStatic
        fun getBuildDate(): String = "Lite/Mock"
    }
}

class FFmpegSession {
    val returnCode: ReturnCode = ReturnCode(255)
    val output: String? = "FFmpeg is running in MOCK mode. Please restore app/libs/ffmpeg vendor to resume functionality."
}

class ReturnCode(val value: Int) {
    companion object {
        @JvmStatic
        fun isSuccess(rc: ReturnCode): Boolean = rc.value == 0

        @JvmStatic
        fun isCancel(rc: ReturnCode): Boolean = rc.value == 255
    }
}

class FFprobeKit {
    companion object {
        @JvmStatic
        fun getMediaInformation(path: String): MediaInformationSession {
            return MediaInformationSession()
        }
    }
}

class MediaInformationSession {
    val mediaInformation: MediaInformation? = null
}

class StreamInformation(
    val index: Long? = null,
    val type: String? = null,
    val codec: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val allProperties: Map<String, Any>? = null
)

class MediaInformation(
    val filename: String? = null,
    val duration: String? = null,
    val size: String? = null,
    val bitrate: String? = null,
    val format: String? = null,
    val streams: List<StreamInformation> = emptyList()
)
