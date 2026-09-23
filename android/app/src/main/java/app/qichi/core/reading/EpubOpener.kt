package app.qichi.core.reading

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File

/** 打不开这本书的原因（给人看的一句话）。 */
class EpubException(message: String) : Exception(message)

/** 书名与作者（加书时从 EPUB 里读出来预填）。 */
data class EpubInfo(val title: String?, val author: String?)

/** 用 Readium 打开本机的 EPUB 文件。 */
class EpubOpener(private val context: Context) {
    private val httpClient = DefaultHttpClient()
    private val assetRetriever = AssetRetriever(context.contentResolver, httpClient)
    private val opener = PublicationOpener(DefaultPublicationParser(context, httpClient, assetRetriever, pdfFactory = null))

    suspend fun open(file: File): Publication = withContext(Dispatchers.IO) {
        val asset = assetRetriever.retrieve(file).getOrNull() ?: throw EpubException("这个文件读不出来")
        opener.open(asset, allowUserInteraction = false).getOrNull() ?: throw EpubException("这本书打不开，可能不是 EPUB")
    }

    suspend fun info(file: File): EpubInfo {
        val publication = open(file)
        return try {
            EpubInfo(publication.metadata.title?.trim()?.ifEmpty { null }, publication.metadata.authors.firstOrNull()?.name?.trim()?.ifEmpty { null })
        } finally {
            publication.close()
        }
    }
}
