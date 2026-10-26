#extension GL_OES_EGL_image_external : require
precision mediump float;

uniform samplerExternalOES uTexture;
varying vec2 vTexCoord;
uniform float uIntensity;

void main() {
    vec4 color = texture2D(uTexture, vTexCoord);
    // Background replacement: apply blur to the entire frame
    // The pipeline composites foreground (person) from original over this
    // uIntensity controls blur strength
    float blurAmount = uIntensity * 3.0;
    
    // Simple box blur approximation
    vec2 texelSize = vec2(1.0) / vec2(640.0, 480.0); // will be overridden by pipeline
    vec4 blurred = vec4(0.0);
    int samples = 0;
    
    for (float x = -blurAmount; x <= blurAmount; x += 1.0) {
        for (float y = -blurAmount; y <= blurAmount; y += 1.0) {
            blurred += texture2D(uTexture, vTexCoord + vec2(x, y) * texelSize * 2.0);
            samples++;
        }
    }
    blurred /= float(samples);
    
    // Darken background slightly
    blurred.rgb *= 0.7;
    
    gl_FragColor = blurred;
}