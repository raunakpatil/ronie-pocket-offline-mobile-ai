package com.raunakpatil.roniepocket.imagegen

import org.json.JSONObject
import java.io.InputStream
import java.util.regex.Pattern

/**
 * Narrow on-device safety gate for image generation.
 *
 * Safety policy:
 * - Ronie does NOT block adult content, adult nudity, violence, or real people ("don't block anything").
 * - The SINGLE hard limit is: NO sexual content involving minors.
 * - This check blocks prompts only when BOTH a minor cue AND a sexual/nudity term are detected.
 * - If a minor cue is present without sexual terms (e.g. "little girl on the beach"), it flags
 *   [needsImageCheck = true] for secondary post-generation visual check.
 */
class MinorsCheck(
    private val minorTerms: List<String>,
    private val minorAgeRegexes: List<Pattern>,
    private val sexualTerms: List<String>,
) {

    data class Result(
        val blocked: Boolean,
        val needsImageCheck: Boolean,
        val minorMatch: String? = null,
        val sexualMatch: String? = null,
    )

    /**
     * Normalizes text by lowercasing, de-leeting common leetspeak substitutions,
     * and removing distracting punctuation.
     */
    fun normalize(text: String): String {
        var s = text.lowercase()
        // Common leetspeak substitutions
        s = s.replace('@', 'a')
            .replace('$', 's')
            .replace('0', 'o')
            .replace('1', 'i')
            .replace('3', 'e')
            .replace('4', 'a')
            .replace('5', 's')
            .replace('7', 't')
            .replace('8', 'b')
            .replace('!', 'i')
            .replace('|', 'l')
        // Replace non-alphanumeric (except space, hyphen, period) with space
        s = s.replace(Regex("""[^a-z0-9\s.\-]"""), " ")
        // Collapse multiple whitespaces
        return s.replace(Regex("""\s+"""), " ").trim()
    }

    fun check(prompt: String): Result {
        val lower = prompt.lowercase()

        // 1. Detect minor cue: check age patterns first on original text
        var minorMatch: String? = null
        for (p in minorAgeRegexes) {
            val m = p.matcher(lower)
            if (m.find()) {
                minorMatch = m.group()
                break
            }
        }

        val norm = normalize(prompt)

        // If no age pattern matched, check minor terms on normalized/de-leeted text
        if (minorMatch == null) {
            for (term in minorTerms) {
                val pattern = Regex("""\b${Pattern.quote(term)}\b""", RegexOption.IGNORE_CASE)
                if (pattern.containsMatchIn(norm)) {
                    minorMatch = term
                    break
                }
            }
        }

        // 2. Detect sexual / nudity term on normalized text
        var sexualMatch: String? = null
        for (term in sexualTerms) {
            val pattern = Regex("""\b${Pattern.quote(term)}\b""", RegexOption.IGNORE_CASE)
            if (pattern.containsMatchIn(norm)) {
                sexualMatch = term
                break
            }
        }

        val hasMinor = minorMatch != null
        val hasSexual = sexualMatch != null

        // Only block if BOTH are present
        val blocked = hasMinor && hasSexual
        // Post-gen image check needed only if minor is mentioned without sexual prompt words
        val needsImageCheck = hasMinor && !hasSexual

        return Result(
            blocked = blocked,
            needsImageCheck = needsImageCheck,
            minorMatch = minorMatch,
            sexualMatch = sexualMatch
        )
    }

    companion object {
        fun fromJson(stream: InputStream): MinorsCheck {
            val json = JSONObject(stream.bufferedReader().use { it.readText() })
            val minors = (0 until json.getJSONArray("minor_terms").length()).map {
                json.getJSONArray("minor_terms").getString(it)
            }
            val agePats = (0 until json.getJSONArray("minor_age_patterns").length()).map {
                Pattern.compile(json.getJSONArray("minor_age_patterns").getString(it), Pattern.CASE_INSENSITIVE)
            }
            val sexual = (0 until json.getJSONArray("sexual_terms").length()).map {
                json.getJSONArray("sexual_terms").getString(it)
            }
            return MinorsCheck(minors, agePats, sexual)
        }
    }
}
