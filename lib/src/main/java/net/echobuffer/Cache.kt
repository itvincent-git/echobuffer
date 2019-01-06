package net.echobuffer

import android.support.v4.util.LruCache

/**
 *
 * @author zhongyongsheng
 */
interface Cache<K, V> {
    fun put(key: K, value: V?)
    operator fun get(key: K): V?
}

class RealCache<K, V>(): Cache<K, V> {
    val maxCacheSize = 128
    val cache = LruCache<K, V>(maxCacheSize)

    override fun put(key: K, value: V?) {
        cache.put(key, value)
    }

    override fun get(key: K): V? {
        return cache.get(key)
    }

}