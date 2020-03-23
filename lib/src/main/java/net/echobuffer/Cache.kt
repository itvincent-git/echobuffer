package net.echobuffer

import android.support.v4.util.LruCache

/**
 * cache the response data
 *
 * @author zhongyongsheng
 */
interface Cache<K, V> {
    fun put(key: K, value: V?)
    operator fun get(key: K): V?
    fun putAll(map: Map<K, V?>)
}

/**
 * real implementation
 */
class RealCache<K, V>(maxCacheSize: Int) : Cache<K, V> {
    private val cache = LruCache<K, V>(maxCacheSize)

    override fun put(key: K, value: V?) {
        value?.let { cache.put(key, it) } ?: cache.remove(key)
    }

    override fun get(key: K): V? {
        return cache.get(key)
    }

    override fun putAll(map: Map<K, V?>) {
        for (i in map) {
            put(i.key, i.value)
        }
    }
}