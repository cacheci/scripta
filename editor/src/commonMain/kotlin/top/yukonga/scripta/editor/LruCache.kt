package top.yukonga.scripta.editor

/**
 * 简单有界 LRU：get 命中与 set 写入都把键移到「最近使用」端，超容量时淘汰最久未用的键。
 * commonMain 没有 java.util 的 accessOrder LinkedHashMap / removeEldestEntry，故手动维护插入顺序。
 *
 * 相比此前「超上限即整表 clear()」——那会连正在显示的行一起丢弃、下一帧整屏重测——LRU 保证刚访问过的
 * 可见窗口永不被一次性淘汰，消除周期性顿挫。
 */
internal class LruCache<K, V>(private val maxSize: Int) {
    private val map = LinkedHashMap<K, V>()

    operator fun get(key: K): V? {
        val v = map.remove(key) ?: return null
        map[key] = v // 重新插到末端 = 标记为最近使用
        return v
    }

    operator fun set(key: K, value: V) {
        map.remove(key)
        map[key] = value
        if (map.size > maxSize) {
            map.remove(map.keys.iterator().next()) // 淘汰队首 = 最久未用
        }
    }

    fun getOrPut(key: K, defaultValue: () -> V): V {
        get(key)?.let { return it }
        val v = defaultValue()
        set(key, v)
        return v
    }

    val size: Int get() = map.size
}
