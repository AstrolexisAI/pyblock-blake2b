package com.astrolexis.pyblock.ui.screens.chat

/** Structured community-chat share cards (kind-42 content) — mirrors iOS `ChatShare`:
 *    pyblock:block?height=904211&kind=clean|lotto        (block celebration)
 *    pyblock:stack?phs=1.50                              (hashrate purchase brag)
 *    pyblock:img?url=https://pyblock.xyz/chat-media/x.jpg (image) */
sealed class ChatShareMsg {
    data class Block(val height: Int, val lotto: Boolean) : ChatShareMsg()
    data class Stack(val phs: Double) : ChatShareMsg()
    data class Image(val url: String) : ChatShareMsg()

    companion object {
        fun parse(content: String): ChatShareMsg? {
            fun query(s: String): Map<String, String> =
                s.substringAfter('?', "").split('&').mapNotNull {
                    val kv = it.split('=', limit = 2)
                    if (kv.size == 2 && kv[0].isNotEmpty()) kv[0] to kv[1] else null
                }.toMap()
            return when {
                content.startsWith("pyblock:block?") -> {
                    val q = query(content)
                    q["height"]?.toIntOrNull()?.let { Block(it, q["kind"] == "lotto") }
                }
                content.startsWith("pyblock:stack?") ->
                    query(content)["phs"]?.toDoubleOrNull()?.takeIf { it > 0 }?.let { Stack(it) }
                content.startsWith("pyblock:img?") ->
                    query(content)["url"]?.takeIf { it.startsWith("http") }?.let { Image(it) }
                else -> null
            }
        }
    }
}
