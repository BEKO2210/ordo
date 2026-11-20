#version 100
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform sampler2D uInput;
uniform sampler2D uResult;
uniform vec2 uOutSize;
uniform float uLuma;
void main() {
  vec2 p = gl_FragCoord.xy / uOutSize;
  vec3 result = texture2D(uResult, p).rgb;
  if (uLuma > 0.5) {
    // The network's luma on the bilinearly doubled colour: only Y' changes.
    vec3 rgb = texture2D(uInput, p).rgb;
    result = rgb + (result.r - dot(rgb, vec3(0.2126, 0.7152, 0.0722)));
  }
  gl_FragColor = vec4(clamp(result, 0.0, 1.0), 1.0);
}