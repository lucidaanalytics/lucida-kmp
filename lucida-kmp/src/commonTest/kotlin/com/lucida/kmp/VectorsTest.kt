package com.lucida.kmp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Shared conformance vectors (testdata/flags/README.md); the path is relative to the module dir. */
class VectorsTest {
    private fun run(name: String) {
        val j = Json.decodeObj(readTestdata("flags/$name.json"))

        @Suppress("UNCHECKED_CAST")
        val hashes = j["hash_vectors"] as List<Obj>
        for (h in hashes) assertEquals((h["hash"] as Number).toDouble(), hashInput(h["input"] as String), "hash ${h["input"]}")

        @Suppress("UNCHECKED_CAST")
        val flags = j["flags"] as List<Obj>

        @Suppress("UNCHECKED_CAST")
        val vectors = j["vectors"] as List<Obj>
        assertTrue(vectors.isNotEmpty())
        for (v in vectors) {
            @Suppress("UNCHECKED_CAST")
            val got = evaluateAll(flags, v["context"] as Obj)[v["flag"]]!!

            @Suppress("UNCHECKED_CAST")
            val want = v["expected"] as Obj
            val n = v["name"]
            assertEquals(want["value"], got["value"], "$n value")
            assertEquals(want["reason"], got["reason"], "$n reason")
            assertEquals((want["condition_index"] as Number).toInt(), got["condition_index"], "$n index")
            assertEquals(want["variant"], got["variant"], "$n variant")
            assertEquals(Json.encode(want["payload"]), Json.encode(got["payload"]), "$n payload")
        }
        println("$name: ${vectors.size} vectors")
    }

    @Test fun basic() = run("vectors_basic")

    @Test fun generated() = run("vectors")

    @Test fun g10() = run("vectors_g10")

    @Test fun sticky() = run("vectors_sticky")

    @Test fun specV2() = run("vectors_ops")
}
