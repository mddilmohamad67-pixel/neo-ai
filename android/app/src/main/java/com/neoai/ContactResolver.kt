package com.neoai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

data class ContactMatch(val name: String, val number: String, val score: Int)

class ContactResolver(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /** Fuzzy search over display names. Empty query means "no search". */
    fun find(query: String, limit: Int = 8): List<ContactMatch> {
        if (!hasPermission()) return emptyList()
        val wanted = normalize(query)
        if (wanted.isBlank()) return emptyList()

        val out = mutableListOf<ContactMatch>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection, null, null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )?.use { c ->
            val nameIdx = c.getColumnIndexOrThrow(projection[0])
            val numIdx = c.getColumnIndexOrThrow(projection[1])
            while (c.moveToNext() && out.size < 400) {
                val name = c.getString(nameIdx) ?: continue
                val number = c.getString(numIdx) ?: continue
                val score = score(wanted, normalize(name))
                if (score > 0) out += ContactMatch(name, number, score)
            }
        }
        return out
            .groupBy { it.number.filter { ch -> ch.isDigit() } }
            .map { (_, list) -> list.maxByOrNull { it.score }!! }
            .sortedWith(compareByDescending<ContactMatch> { it.score }.thenBy { it.name })
            .take(limit)
    }

    /** Single confident match, or null. */
    fun best(query: String): ContactMatch? {
        val results = find(query)
        if (results.isEmpty()) return null
        val top = results[0]
        val second = results.getOrNull(1)
        val clearWinner = second == null || top.score - second.score >= 25 || top.name.equals(query, true)
        return if (top.score >= 70 && clearWinner) top else null
    }

    fun isAmbiguous(query: String): Boolean {
        val results = find(query)
        if (results.size < 2) return false
        return results[0].score - results[1].score < 25
    }

    fun all(): List<ContactMatch> {
        if (!hasPermission()) return emptyList()
        return find("a") + find("e") + find("i") + find("o") + find("u")
    }

    private fun normalize(input: String): String =
        input.lowercase()
            .replace(Regex("[^\\p{L}\\p{N} ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun score(wanted: String, name: String): Int {
        if (name.isBlank()) return 0
        if (name == wanted) return 100
        if (name.startsWith(wanted)) return 92
        if (name.split(" ").any { it == wanted }) return 90
        if (name.contains(wanted)) return 80
        val tokens = wanted.split(" ").filter { it.length > 1 }
        if (tokens.isNotEmpty() && tokens.all { name.contains(it) }) return 75
        val distance = levenshtein(wanted, name.take(wanted.length.coerceAtLeast(1)))
        val similarity = 100 - (distance * 100 / wanted.length.coerceAtLeast(1))
        return if (similarity >= 65) similarity else 0
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            prev.indices.forEach { prev[it] = cur[it] }
        }
        return prev[b.length]
    }
}
