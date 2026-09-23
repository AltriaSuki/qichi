package app.qichi.server.review

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.jvm.javaio.copyTo
import java.nio.file.Files
import java.nio.file.Path

/** 把 Word、Excel、PowerPoint 等转成 PDF（预览再从 PDF 生成）。 */
fun interface DocumentConverter {
    /** [extension] 决定按什么格式读（docx、xlsx、pptx……）。转不了抛 [PreviewFailure]；暂时出错抛别的异常（会重试）。 */
    suspend fun toPdf(input: Path, extension: String, output: Path)
}

/**
 * Gotenberg（docker 镜像 gotenberg/gotenberg，内含 LibreOffice 无界面模式），单独一个 converter 容器，
 * 只在内部网络里，不对外。接口：POST /forms/libreoffice/convert，表单字段 files。
 */
class GotenbergConverter(private val baseUrl: String, engine: HttpClientEngine = CIO.create()) : DocumentConverter {
    private val http = HttpClient(engine) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 5 * 60_000
        }
    }

    override suspend fun toPdf(input: Path, extension: String, output: Path) {
        val bytes = Files.readAllBytes(input)
        val response = http.submitFormWithBinaryData(
            url = "$baseUrl/forms/libreoffice/convert",
            formData = formData {
                append("files", bytes, Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"input.$extension\"") })
            },
        )
        when (response.status.value) {
            in 200..299 -> Files.newOutputStream(output).use { response.bodyAsChannel().copyTo(it) }
            400, 415, 422 -> throw PreviewFailure("这个文件转换不了，可以另存为 PDF 再传")
            else -> error("文档转换服务出错：HTTP ${response.status.value}")
        }
    }
}
