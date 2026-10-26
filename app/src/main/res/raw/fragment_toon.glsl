#extension GL_OES_EGL_image_external : require
precision mediump float;

uniform samplerExternalOES uTexture;
uniform vec2 uTexelSize;

varying vec2 vTexCoord;

void main() {
    vec4 color = texture2D(uTexture, vTexCoord);

    // Edge detection (Sobel magnitude)
    float samples[9];
    int idx = 0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 offset = vec2(float(x), float(y)) * uTexelSize;
            vec4 s = texture2D(uTexture, vTexCoord + offset);
            samples[idx] = dot(s.rgb, vec3(0.299, 0.587, 0.114));
            idx++;
        }
    }

    float gx = -samples[0] + samples[2]
               -2.0 * samples[3] + 2.0 * samples[5]
               -samples[6] + samples[8];

    float gy = -samples[0] - 2.0 * samples[1] - samples[2]
               +samples[6] + 2.0 * samples[7] + samples[8];

    float edge = sqrt(gx * gx + gy * gy);

    // Posterize to 5 color levels
    float levels = 5.0;
    vec3 posterized = floor(color.rgb * levels + 0.5) / levels;

    // Draw black outline where edges are detected
    float edgeMask = 1.0 - smoothstep(0.05, 0.2, edge);
    vec3 result = posterized * edgeMask;

    gl_FragColor = vec4(result, color.a);
}
