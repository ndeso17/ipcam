#extension GL_OES_EGL_image_external : require
precision mediump float;

uniform samplerExternalOES uTexture;
uniform vec2 uTexelSize;

varying vec2 vTexCoord;

void main() {
    // 9-tap Gaussian blur (3x3 kernel: 1/16 * [1 2 1; 2 4 2; 1 2 1])
    vec4 result = vec4(0.0);

    result += texture2D(uTexture, vTexCoord + vec2(-1.0, -1.0) * uTexelSize) * 1.0;
    result += texture2D(uTexture, vTexCoord + vec2( 0.0, -1.0) * uTexelSize) * 2.0;
    result += texture2D(uTexture, vTexCoord + vec2( 1.0, -1.0) * uTexelSize) * 1.0;

    result += texture2D(uTexture, vTexCoord + vec2(-1.0,  0.0) * uTexelSize) * 2.0;
    result += texture2D(uTexture, vTexCoord                     ) * 4.0;
    result += texture2D(uTexture, vTexCoord + vec2( 1.0,  0.0) * uTexelSize) * 2.0;

    result += texture2D(uTexture, vTexCoord + vec2(-1.0,  1.0) * uTexelSize) * 1.0;
    result += texture2D(uTexture, vTexCoord + vec2( 0.0,  1.0) * uTexelSize) * 2.0;
    result += texture2D(uTexture, vTexCoord + vec2( 1.0,  1.0) * uTexelSize) * 1.0;

    gl_FragColor = result / 16.0;
}
