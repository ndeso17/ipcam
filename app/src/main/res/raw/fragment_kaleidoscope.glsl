#extension GL_OES_EGL_image_external : require
precision mediump float;

uniform samplerExternalOES uTexture;
uniform float uSegments;

varying vec2 vTexCoord;

void main() {
    const float PI = 3.14159265359;

    // Center UV to [-1, 1]
    vec2 uv = vTexCoord * 2.0 - 1.0;

    // Polar coordinates
    float angle = atan(uv.y, uv.x);
    float radius = length(uv);

    // Mirror into triangular segments
    float segAngle = 2.0 * PI / uSegments;
    angle = mod(angle, segAngle);
    angle = abs(angle - segAngle * 0.5);

    // Convert back to Cartesian
    vec2 mirrored = vec2(cos(angle), sin(angle)) * radius;

    // Map back to [0, 1]
    vec2 sampleUV = mirrored * 0.5 + 0.5;

    // Clamp to valid range
    sampleUV = clamp(sampleUV, 0.0, 1.0);

    gl_FragColor = texture2D(uTexture, sampleUV);
}
