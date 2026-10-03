package app.qichi.core.network

/**
 * 上传、下载的进度：网络库每收发一小块数据都回调一次（一个 20MB 的文件几千次），
 * 每次都更新界面，列表在传文件时一直重绘、发卡。这里只在整百分比变了时才报给 [onProgress]（0–1）。
 * 回调来自同一个传输，一个接一个，不会同时来。
 */
fun percentProgress(onProgress: (Float) -> Unit): (done: Long, total: Long) -> Unit {
    var last = -1
    return { done, total ->
        if (total > 0) {
            val percent = (done * 100 / total).toInt().coerceIn(0, 100)
            if (percent != last) {
                last = percent
                onProgress(percent / 100f)
            }
        }
    }
}
