package com.raunakpatil.roniepocket.imagegen

/**
 * Prompt engineering and negative prompt synthesizer tailored specifically for SANA-Sprint
 * and Gemma-2-2B text conditioning.
 *
 * SANA's linear Diffusion Transformer is conditioned on Gemma-2-2B semantic embeddings.
 * Gemma-2 was trained on dense synthetic recaptioning (100–250 tokens per caption) detailing:
 * 1. Subject description with explicit anatomical integrity (limb count, hands, fingers, symmetry)
 * 2. Spatial placement and environmental background
 * 3. Directional lighting, atmosphere, and depth of field
 * 4. Medium-specific art aesthetics and texture
 * 5. Negative constraint guidance ("avoiding: ...") to prevent extra limbs, deformed fingers, and blur.
 */
object SanaPromptSynthesizer {

    const val DEFAULT_NEGATIVE_PROMPT =
        "extra limbs, missing limbs, bad anatomy, deformed hands, extra fingers, mutated fingers, fused limbs, distorted body, duplicate figures, deformed face, floating limbs, malformed joints, blurry, low resolution, artifacts"

    private val HUMAN_KEYWORDS = listOf(
        "person", "man", "woman", "girl", "boy", "warrior", "knight", "astronaut", "samurai",
        "ninja", "lady", "model", "dancer", "soldier", "witch", "wizard", "monk", "hero",
        "portrait", "face", "nude", "naked", "sensual", "human", "character", "people"
    )

    private val ANIMAL_KEYWORDS = listOf(
        "cat", "kitten", "dog", "puppy", "wolf", "fox", "horse", "dragon", "bird", "lion",
        "tiger", "bear", "rabbit", "deer", "eagle", "falcon", "owl", "animal", "creature", "beast"
    )

    /**
     * Synthesizes a user prompt into a high-fidelity prompt specifically structured for SANA.
     *
     * @param rawPrompt The user or LLM prompt.
     * @param style Optional style hint (e.g. "watercolour", "photo", "anime").
     * @param userNegative Optional user negative prompt.
     * @return Pair of (detailedPrompt, comprehensiveNegativePrompt).
     */
    fun synthesize(
        rawPrompt: String,
        style: String? = null,
        userNegative: String? = null
    ): Pair<String, String> {
        val clean = rawPrompt.trim()
        val lower = clean.lowercase()

        // 1. Determine Negative Prompt
        val finalNegative = if (!userNegative.isNullOrBlank()) {
            val userTokens = userNegative.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val defaultTokens = DEFAULT_NEGATIVE_PROMPT.split(",").map { it.trim() }
            (userTokens + defaultTokens).distinct().joinToString(", ")
        } else {
            DEFAULT_NEGATIVE_PROMPT
        }

        // 2. Anatomical Integrity Safeguards
        val isHuman = HUMAN_KEYWORDS.any { lower.contains(it) }
        val isAnimal = ANIMAL_KEYWORDS.any { lower.contains(it) }

        val anatomyGuard = when {
            isHuman && !lower.contains("anatomically") && !lower.contains("fingers") ->
                "anatomically correct human body, proportionate limbs, cleanly rendered hands with five distinct fingers, symmetrical facial features, natural posture, no extra limbs, no missing limbs, no mutated hands, no fused fingers"
            isAnimal && !lower.contains("anatomically") && !lower.contains("paws") ->
                "anatomically correct animal anatomy, well-formed paws and limbs, proportionate body structure, natural posture, no extra paws, no duplicate limbs"
            !lower.contains("coherent") && !lower.contains("perspective") ->
                "coherent geometry, clean perspective, centered composition, sharp focal point, crisp edges"
            else -> ""
        }

        // 3. Style and Texture Enhancement
        val effectiveStyle = style?.lowercase() ?: when {
            lower.contains("watercolour") || lower.contains("watercolor") -> "watercolour"
            lower.contains("photo") || lower.contains("realistic") || lower.contains("photorealistic") -> "photo"
            lower.contains("anime") || lower.contains("manga") -> "anime"
            lower.contains("pixel art") -> "pixel art"
            lower.contains("sketch") || lower.contains("pencil") -> "sketch"
            lower.contains("3d") || lower.contains("render") || lower.contains("cgi") -> "3d"
            lower.contains("oil") || lower.contains("oil painting") -> "oil painting"
            else -> null
        }

        val styleBooster = when (effectiveStyle) {
            "watercolour", "watercolor" ->
                if (!lower.contains("cotton paper") && !lower.contains("washes"))
                    "delicate watercolor painting, vibrant washes, fine pigment dispersion, textured cold-press cotton paper, soft blending edges, masterwork"
                else ""
            "photo", "realistic", "photorealistic" ->
                if (!lower.contains("35mm") && !lower.contains("cinematic"))
                    "award-winning 35mm photography, 8k resolution, photorealistic textures, crisp sharp focus, cinematic lighting, masterpiece"
                else ""
            "anime", "manga" ->
                if (!lower.contains("lineart") && !lower.contains("cel shading"))
                    "vibrant anime masterpiece, crisp detailed lineart, beautiful cel shading, expressive aesthetics, studio quality"
                else ""
            "pixel art" ->
                if (!lower.contains("sprites") && !lower.contains("16-bit"))
                    "detailed pixel art, clean sprites, 16-bit aesthetic, vibrant color palette"
                else ""
            "sketch", "pencil" ->
                if (!lower.contains("cross-hatching") && !lower.contains("graphite"))
                    "fine graphite pencil sketch, delicate cross-hatching, expressive line work, clean paper texture"
                else ""
            "3d", "render", "cgi" ->
                if (!lower.contains("subsurface") && !lower.contains("raytraced"))
                    "detailed 3D render, smooth volumetric lighting, subsurface scattering, raytraced ambient occlusion, 8k"
                else ""
            "oil", "oil painting" ->
                if (!lower.contains("impasto") && !lower.contains("brushstrokes"))
                    "rich oil painting on canvas, visible textured brushstrokes, classical impasto technique, vibrant palette"
                else ""
            else ->
                if (!lower.contains("masterpiece") && !lower.contains("detailed"))
                    "masterpiece, highly detailed, sharp focus, 8k resolution, immaculate execution"
                else ""
        }

        // 4. Assemble the enriched prompt
        val segments = mutableListOf<String>()
        segments.add(clean)
        if (styleBooster.isNotBlank() && !clean.contains(styleBooster.split(",")[0], ignoreCase = true)) {
            segments.add(styleBooster)
        }
        if (anatomyGuard.isNotBlank()) {
            segments.add(anatomyGuard)
        }

        val detailedPrompt = segments.joinToString(", ")
        return detailedPrompt to finalNegative
    }

    /**
     * Formats the final prompt for Gemma-2-2B SentencePiece encoding,
     * cleanly integrating negative guardrails into natural language context.
     */
    fun formatForGemma(prompt: String, negativePrompt: String): String {
        val cleanPrompt = prompt.trim()
        val cleanNegative = negativePrompt.trim()
        if (cleanNegative.isBlank() || cleanPrompt.contains("avoiding:", ignoreCase = true)) {
            return cleanPrompt
        }
        return "$cleanPrompt, clean render avoiding: $cleanNegative"
    }
}
