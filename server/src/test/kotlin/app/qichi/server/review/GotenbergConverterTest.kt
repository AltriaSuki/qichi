package app.qichi.server.review

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 文档转换（B3）：文件边读边传，不先整份读进内存（审稿文件最大 100MB，服务端的堆只有 256MB）。 */
class GotenbergConverterTest {
    private val dir = Files.createTempDirectory("qichi-convert-")

    @OptIn(InternalAPI::class)
    @Test
    fun `上传的文件从磁盘边读边传，转好的 PDF 写到输出文件`() = runBlocking {
        val input = dir.resolve("合同.docx")
        val content = ByteArray(300_000) { (it % 251).toByte() }
        Files.write(input, content)
        var part: PartData? = null
        var url = ""
        var body = ByteArray(0)
        val engine = MockEngine { request ->
            url = request.url.toString()
            part = (request.body as MultiPartFormDataContent).parts.single()
            body = request.body.toByteArray()
            respond("%PDF-1.7 转好了".toByteArray(), HttpStatusCode.OK)
        }
        val output = dir.resolve("out.pdf")
        GotenbergConverter("http://converter:3000", engine).toPdf(input, "docx", output)

        assertEquals("http://converter:3000/forms/libreoffice/convert", url)
        // 按通道从文件读（旧写法是整份读成字节数组放进表单）
        assertIs<PartData.BinaryChannelItem>(part)
        assertEquals("files", part!!.name)
        val text = String(body, Charsets.ISO_8859_1)
        assertTrue(text.contains("filename=\"input.docx\""), "按扩展名告诉转换服务是什么格式")
        assertTrue(text.contains(String(content, Charsets.ISO_8859_1)), "表单里要有完整的文件内容")
        assertContentEquals("%PDF-1.7 转好了".toByteArray(), Files.readAllBytes(output))
    }

    @Test
    fun `转换不了告诉人原因；服务出错抛普通异常（任务会重试）`() = runBlocking {
        val input = dir.resolve("a.xlsx").also { Files.write(it, byteArrayOf(1, 2, 3)) }
        val rejected = GotenbergConverter("http://converter:3000", MockEngine { respondError(HttpStatusCode.UnprocessableEntity) })
        val failure = assertFailsWith<PreviewFailure> { rejected.toPdf(input, "xlsx", dir.resolve("x.pdf")) }
        assertEquals("这个文件转换不了，可以另存为 PDF 再传", failure.message)

        val down = GotenbergConverter("http://converter:3000", MockEngine { respondError(HttpStatusCode.ServiceUnavailable) })
        val error = assertFailsWith<IllegalStateException> { down.toPdf(input, "xlsx", dir.resolve("y.pdf")) }
        assertTrue(error.message!!.contains("503"))
    }
}
