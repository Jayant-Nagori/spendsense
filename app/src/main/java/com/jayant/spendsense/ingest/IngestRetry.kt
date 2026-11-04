package com.jayant.spendsense.ingest

/** One retry for transient failures (e.g. the encrypted DB briefly locked); a capture should not be lost to a blip. */
internal inline fun ingestWithRetry(block: () -> Unit) {
    if (runCatching(block).isSuccess) return
    Thread.sleep(400)
    runCatching(block)
}
