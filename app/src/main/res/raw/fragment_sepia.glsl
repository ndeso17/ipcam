#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES uTexture;
varying vec2 vTexCoord;
void main() {
    vec4 color = texture2D(uTexture, vTexCoord);
    float gray = dot(color.rgb, vec3(0.299, 0.587, 0.114));
    gl_FragColor = vec4(
        min(gray * 1.2, 1.0),
        min(gray * 0.95, 1.0),
        min(gray * 0.75, 1.0),
        color.a
    );
}
