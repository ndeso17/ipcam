#extension GL_OES_EGL_image_external : require
precision mediump float;

uniform samplerExternalOES uTexture;
uniform vec2 uTexelSize;
uniform float uIntensity;

varying vec2 vTexCoord;

void main() {
    vec4 color = texture2D(uTexture, vTexCoord);

    // 9-tap blur (3x3 box)
    vec4 blurred = vec4(0.0);
    for (int x = -1; x <= 1; x++) {
        for (int y = -1; y <= 1; y++) {
            vec2 offset = vec2(float(x), float(y)) * uTexelSize;
            blurred += texture2D(uTexture, vTexCoord + offset);
        }
    }
    blurred /= 9.0;

    // Additive blend: original + soft glow
    gl_FragColor = color + blurred * uIntensity;
}
