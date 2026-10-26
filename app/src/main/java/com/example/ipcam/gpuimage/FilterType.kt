package com.example.ipcam.gpuimage

import com.example.ipcam.R

enum class FilterType(val displayName: String, val fragmentShaderRes: Int) {
    NONE("None", 0),
    BEAUTY("Beauty", R.raw.fragment_glow),
    SEPIA("Sepia", R.raw.fragment_sepia),
    GRAYSCALE("Grayscale", R.raw.fragment_grayscale),
    NEGATIVE("Negative", R.raw.fragment_negative),
    VIGNETTE("Vignette", R.raw.fragment_vignette),
    BRIGHTNESS("Brightness", R.raw.fragment_brightness),
    CONTRAST("Contrast", R.raw.fragment_contrast),
    SATURATION("Saturation", R.raw.fragment_saturation),
    GLOW("Glow", R.raw.fragment_glow),
    SKETCH("Sketch", R.raw.fragment_sketch),
    TOON("Toon", R.raw.fragment_toon),
    PIXELATE("Pixelate", R.raw.fragment_pixelate),
    KALEIDOSCOPE("Kaleidoscope", R.raw.fragment_kaleidoscope),
    BLUR("Blur", R.raw.fragment_blur),
    BG_REPLACE("BG Replace", R.raw.fragment_bg_replace);
}
