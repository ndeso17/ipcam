#extension GL_OES_EGL_image_external : require
precision mediump float;

uniform samplerExternalOES uTexture;
uniform float uPixelSize;

varying vec2 vTexCoord;

void main() {
    // Snap UV to grid
    vec2 grid = vec2(uPixelSize);
    vec2 uv = floor(vTexCoord / grid) * grid + grid * 0.5;

    gl_FragColor = texture2D(uTexture, uv);
}
