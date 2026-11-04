package br.com.plusapps.s23logvideo.probe

import android.util.Range
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Modifier

/** Converte constantes inteiras do Android em nomes legíveis, por reflexão nos campos públicos. */
object Names {
    private val cache = HashMap<String, Map<Long, String>>()

    fun table(cls: Class<*>, prefix: String): Map<Long, String> = synchronized(cache) {
        cache.getOrPut(cls.name + "#" + prefix) {
            val map = LinkedHashMap<Long, String>()
            for (f in cls.fields) {
                if (!Modifier.isStatic(f.modifiers) || !f.name.startsWith(prefix)) continue
                val t = f.type
                if (t != Int::class.javaPrimitiveType && t != Long::class.javaPrimitiveType) continue
                val v = (f.get(null) as Number).toLong()
                map.putIfAbsent(v, f.name.removePrefix(prefix))
            }
            map
        }
    }

    fun of(cls: Class<*>, prefix: String, value: Number): String =
        table(cls, prefix)[value.toLong()] ?: "UNKNOWN_$value"

    fun list(cls: Class<*>, prefix: String, values: IntArray?): JSONArray =
        JSONArray().apply { values?.forEach { put(of(cls, prefix, it)) } }
}

fun Size.str() = "${width}x$height"

fun <T : Comparable<T>> Range<T>.json(): JSONArray = JSONArray().put(lower).put(upper)

fun JSONObject.putSafe(key: String, block: () -> Any?): JSONObject {
    try {
        put(key, block() ?: JSONObject.NULL)
    } catch (t: Throwable) {
        put(key, "ERRO: $t")
    }
    return this
}
