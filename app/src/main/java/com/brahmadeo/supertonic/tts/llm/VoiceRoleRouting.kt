package com.brahmadeo.supertonic.tts.llm

/** Provider failures are transient; only complete, validated roles count as success. */
internal object VoiceRoleRouting {
    data class Result(val plans: List<List<VoiceRoleText>?>, val providers: List<String?>)
    private data class Slice(val paragraph: Int, val text: String)

    fun resolve(texts: List<String>, providers: List<String>, preceding: String,
                available: (String) -> Boolean,
                request: (String, List<String>, String) -> List<List<VoiceRoleText>?>,
                failed: (String, Exception) -> Unit = { _, _ -> },
                resolved: (Int, List<VoiceRoleText>, String) -> Unit = { _, _, _ -> }): Result {
        val plans = MutableList<List<VoiceRoleText>?>(texts.size) { null }
        val used = MutableList<String?>(texts.size) { null }
        for (provider in providers.distinct()) {
            if (!available(provider)) continue
            val local = provider == "local"
            val maxChars = if (local) 1000 else 2000
            val slices = texts.indices.filter { plans[it] == null }.flatMap { index ->
                val parts = mutableListOf<String>()
                var part = ""
                for (unit in VoiceRolePlan.units(texts[index])) {
                    val next = part + unit
                    if (part.isNotEmpty() && (next.length > maxChars ||
                                (local && LocalVoiceRoleProtocol.fragments(next).size > 24))) {
                        parts += part; part = ""
                    }
                    part += unit
                }
                if (part.isNotEmpty()) parts += part
                parts.map { Slice(index, it) }
            }
            val collected = mutableMapOf<Int, MutableList<VoiceRoleText>>()
            val invalid = mutableSetOf<Int>()
            var offset = 0
            while (offset < slices.size) {
                if (!available(provider)) break
                val chunk = mutableListOf<Slice>()
                var chars = 0
                var pieces = 0
                while (offset < slices.size) {
                    val slice = slices[offset]
                    val count = if (local) LocalVoiceRoleProtocol.fragments(slice.text).size else 0
                    if (chunk.isNotEmpty() && (chars + slice.text.length > maxChars || chunk.size >= 8 || pieces + count > 24)) break
                    chunk += slice; offset++; chars += slice.text.length; pieces += count
                }
                try {
                    val before = (preceding + "\n" + slices.take(offset - chunk.size).joinToString("") { it.text }).takeLast(if (local) 600 else 1800)
                    val answer = request(provider, chunk.map { it.text }, before)
                    require(answer.size == chunk.size)
                    chunk.forEachIndexed { i, slice ->
                        val plan = answer[i]
                        if (plan == null || VoiceRolePlan.safe(slice.text, plan) != plan) invalid += slice.paragraph
                        else collected.getOrPut(slice.paragraph) { mutableListOf() }.addAll(plan)
                    }
                } catch (e: Exception) {
                    chunk.forEach { invalid += it.paragraph }
                    failed(provider, e)
                    // A network failure should not trigger one failed call for every slice.
                    slices.drop(offset).forEach { invalid += it.paragraph }
                    break
                } catch (e: LinkageError) {
                    chunk.forEach { invalid += it.paragraph }
                    slices.drop(offset).forEach { invalid += it.paragraph }
                    failed(provider, IllegalStateException("Local runtime unavailable", e))
                    break
                }
                collected.forEach { (i, segments) ->
                    if (i !in invalid && plans[i] == null) {
                        val merged = mutableListOf<VoiceRoleText>()
                        segments.forEach { part ->
                            if (merged.lastOrNull()?.role == part.role) {
                                val old = merged.removeAt(merged.lastIndex)
                                merged += old.copy(text = old.text + part.text)
                            } else merged += part
                        }
                        if (VoiceRolePlan.safe(texts[i], merged) == merged) { plans[i] = merged; used[i] = provider; resolved(i, merged, provider) }
                    }
                }
            }
            if (plans.all { it != null }) break
        }
        return Result(plans, used)
    }
}
