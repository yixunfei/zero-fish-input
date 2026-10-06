package dev.zeroinput.ime.ui

enum class KeyboardTheme(val label: Int, val overlay: Int) {
    CLASSIC(R.string.keyboard_theme_classic, R.style.ThemeOverlay_ZeroInput_Keyboard_Classic),
    GRAY(R.string.keyboard_theme_gray, R.style.ThemeOverlay_ZeroInput_Keyboard_Gray),
    MINT(R.string.keyboard_theme_mint, R.style.ThemeOverlay_ZeroInput_Keyboard_Mint),
    ROSE(R.string.keyboard_theme_rose, R.style.ThemeOverlay_ZeroInput_Keyboard_Rose),
}

enum class KeyboardHeight(val label: Int, private val portrait: Int, private val landscape: Int) {
    COMPACT(R.string.keyboard_height_compact, 48, 48),
    STANDARD(R.string.keyboard_height_standard, 52, 48),
    COMFORTABLE(R.string.keyboard_height_comfortable, 60, 52);

    fun rowHeight(landscape: Boolean): Int = if (landscape) this.landscape else portrait
}

enum class KeyboardMaterial(val label: Int) {
    CLASSIC(R.string.material_classic), FLAT(R.string.material_flat),
    RAISED(R.string.material_raised), SOFT(R.string.material_soft),
    METAL(R.string.material_metal), FROSTED(R.string.material_frosted),
}

enum class KeyboardBackground(val label: Int) {
    SOLID(R.string.background_solid), GRADIENT(R.string.background_gradient),
    TEXTURE(R.string.background_texture), IMAGE(R.string.background_image),
}

data class KeyboardAppearance(
    val theme: KeyboardTheme = KeyboardTheme.CLASSIC,
    val height: KeyboardHeight = KeyboardHeight.STANDARD,
    val material: KeyboardMaterial = KeyboardMaterial.FLAT,
    val borders: Boolean = false,
    val cornerRadius: Int = 8,
    val keySpacing: Int = 2,
    val background: KeyboardBackground = KeyboardBackground.SOLID,
    val backgroundColor: Int? = null,
    val backgroundOpacity: Int = 100,
    val backgroundDim: Int = 15,
    val backgroundBlur: Int = 0,
    val imageRevision: String = "",
) {
    fun sanitized(): KeyboardAppearance = copy(
        cornerRadius = cornerRadius.coerceIn(0, 20), keySpacing = keySpacing.coerceIn(0, 6),
        backgroundOpacity = backgroundOpacity.coerceIn(0, 100), backgroundDim = backgroundDim.coerceIn(0, 80),
        backgroundBlur = backgroundBlur.coerceIn(0, 20),
        backgroundColor = backgroundColor?.let { it or 0xff000000.toInt() },
        imageRevision = imageRevision.takeIf { it.matches(Regex("[a-f0-9-]{36}")) }.orEmpty(),
    )
}
