#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES uTexture;
uniform float uIntensity;
varying vec2 vTexCoord;
void main() {
    vec4 color = texture2D(uTexture, vTexCoord);
    gl_FragColor = vec4((color.rgb - 0.5) * (1.0 + uIntensity) + 0.5, color.a);
}
