#extension GL_OES_EGL_image_external : require
precision mediump float;

uniform samplerExternalOES uTexture;
uniform vec2 uTexelSize;

varying vec2 vTexCoord;

void main() {
    // Sobel kernels
    // Gx:        Gy:
    // -1  0  1   -1 -2 -1
    // -2  0  2    0  0  0
    // -1  0  1    1  2  1

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

    // Black edges on white background
    float val = 1.0 - smoothstep(0.05, 0.3, edge);
    gl_FragColor = vec4(vec3(val), 1.0);
}
