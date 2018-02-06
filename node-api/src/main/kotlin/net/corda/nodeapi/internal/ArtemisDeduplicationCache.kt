package net.corda.nodeapi.internal

import com.google.common.cache.CacheBuilder

class ArtemisDeduplicationCache(cacheSize: Long) {
    private val messageIdCache =
            CacheBuilder.newBuilder()
                    .maximumSize(cacheSize)
                    .build<Long, Unit>()

    fun checkDuplicateMessageId(messageId: Long): Boolean {
        var isDuplicate = true
        messageIdCache.get(messageId) {
            isDuplicate = false
        }
        return isDuplicate
    }
}
