attribute vec4 position;
attribute vec4 texCoord;
varying vec2 vTexCoord;
void main() {
    gl_Position = position;
    vTexCoord = texCoord.xy;
}
