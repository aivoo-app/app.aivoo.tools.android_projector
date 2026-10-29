package app.aivoo.tools.androidprojector.model

enum class ContentType { PDF, VIDEO, IMAGE, URL, UNKNOWN }

data class UploadedFile(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val path: String,
    val mimeType: String? = null,
    val type: ContentType = ContentType.UNKNOWN,
    val size: Long = 0L,
)
