// WebGL2 host for the exact GLSL ES 3 programs shipped in the Android app.
function shader(gl, type, source, label) {
  const object = gl.createShader(type);
  gl.shaderSource(object, source);
  gl.compileShader(object);
  if (!gl.getShaderParameter(object, gl.COMPILE_STATUS)) {
    const log = gl.getShaderInfoLog(object);
    gl.deleteShader(object);
    throw new Error(`Could not compile ${label}: ${log}`);
  }
  return object;
}

function link(gl, vertex, source, label) {
  const fragment = shader(gl, gl.FRAGMENT_SHADER, source, label);
  const id = gl.createProgram();
  gl.attachShader(id, vertex);
  gl.attachShader(id, fragment);
  gl.linkProgram(id);
  gl.deleteShader(fragment);
  if (!gl.getProgramParameter(id, gl.LINK_STATUS)) {
    const log = gl.getProgramInfoLog(id);
    gl.deleteProgram(id);
    throw new Error(`Could not link ${label}: ${log}`);
  }
  const u = {};
  const count = gl.getProgramParameter(id, gl.ACTIVE_UNIFORMS);
  for (let i = 0; i < count; i++) {
    const { name } = gl.getActiveUniform(id, i);
    u[name] = gl.getUniformLocation(id, name);
  }
  return { id, u };
}

class Target {
  constructor(gl) {
    this.gl = gl;
    this.width = this.height = 0;
    this.texture = this.framebuffer = null;
  }

  bind(width, height) {
    const gl = this.gl;
    if (!this.framebuffer) {
      this.texture = gl.createTexture();
      this.framebuffer = gl.createFramebuffer();
      gl.activeTexture(gl.TEXTURE0);
      gl.bindTexture(gl.TEXTURE_2D, this.texture);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      gl.bindFramebuffer(gl.FRAMEBUFFER, this.framebuffer);
      gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, this.texture, 0);
    } else gl.bindFramebuffer(gl.FRAMEBUFFER, this.framebuffer);
    if (width !== this.width || height !== this.height) {
      gl.activeTexture(gl.TEXTURE0);
      gl.bindTexture(gl.TEXTURE_2D, this.texture);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, width, height, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
      if (gl.checkFramebufferStatus(gl.FRAMEBUFFER) !== gl.FRAMEBUFFER_COMPLETE) {
        throw new Error(`GPU could not allocate a ${width} × ${height} visual target.`);
      }
      this.width = width;
      this.height = height;
    }
    gl.viewport(0, 0, width, height);
  }

  release() {
    this.gl.deleteTexture(this.texture);
    this.gl.deleteFramebuffer(this.framebuffer);
    this.texture = this.framebuffer = null;
    this.width = this.height = 0;
  }
}

export const shaderNames = {
  vertex: 'visualizer.vert',
  scene: 'visualizer.frag',
  mix: 'post_mix.frag',
  blur: 'post_blur.frag',
  composite: 'post_composite.frag',
};

const floatUniforms = {
  intensity: 'uIntensity',
  complexity: 'uComplexity',
  symmetry: 'uSymmetry',
  distortion: 'uDistortion',
  zoom: 'uZoom',
  lineWidth: 'uLineWidth',
  hue: 'uHue',
  saturation: 'uSaturation',
  contrast: 'uContrast',
  kaleidoscopeSpace: 'uFractalSpace',
};

export class Pipeline {
  constructor(gl, shaders) {
    this.gl = gl;
    this.programs = {};
    this.targets = {};
    this.source = null;
    const vertex = shader(gl, gl.VERTEX_SHADER, shaders.vertex, shaderNames.vertex);
    try {
      for (const name of ['scene', 'mix', 'blur', 'composite']) {
        this.programs[name] = link(gl, vertex, shaders[name], shaderNames[name]);
      }
      this.vao = gl.createVertexArray();
      this.buffer = gl.createBuffer();
      gl.bindVertexArray(this.vao);
      gl.bindBuffer(gl.ARRAY_BUFFER, this.buffer);
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
      gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
      gl.enableVertexAttribArray(0);
      gl.disable(gl.DEPTH_TEST);
      this.maxEdge = Math.min(gl.getParameter(gl.MAX_TEXTURE_SIZE), ...gl.getParameter(gl.MAX_VIEWPORT_DIMS));
    } catch (error) {
      this.dispose();
      throw error;
    } finally {
      gl.deleteShader(vertex);
    }
  }

  target(name, width, height) {
    const target = (this.targets[name] ??= new Target(this.gl));
    target.bind(width, height);
    return target;
  }

  use(name) {
    const program = this.programs[name];
    this.gl.useProgram(program.id);
    return program.u;
  }

  texture(unit, location, target) {
    const gl = this.gl;
    gl.activeTexture(gl.TEXTURE0 + unit);
    gl.bindTexture(gl.TEXTURE_2D, target.texture);
    gl.uniform1i(location, unit);
  }

  quad() {
    this.gl.drawArrays(this.gl.TRIANGLE_STRIP, 0, 4);
  }

  scene(target, layer, motion, pan, previousScene, transition) {
    const gl = this.gl,
      s = layer.settings,
      u = this.use('scene');
    gl.uniform2f(u.uResolution, target.width, target.height);
    gl.uniform2f(u.uTouch, pan[0], pan[1]);
    gl.uniform1f(u.uTime, layer.time);
    gl.uniform1f(u.uRotation, layer.rotation);
    gl.uniform1f(u.uMorphTime, layer.morphTime);
    gl.uniform1f(u.uColorPhase, layer.colorPhase);
    for (const [key, name] of Object.entries(floatUniforms)) gl.uniform1f(u[name], s[key]);
    gl.uniform1i(u.uMode, s.scene);
    gl.uniform1i(u.uPreviousMode, previousScene ?? s.scene);
    gl.uniform1f(u.uTransition, transition ?? 1);
    gl.uniform1i(u.uPalette, s.palette);
    gl.uniform1i(u.uFractalShape, s.kaleidoscopeShape);
    gl.uniform3fv(u.uFractalBands, layer.bands);
    gl.uniform4f(u.uRhythm, motion.beat, motion.bar, motion.flow, motion.texture);
    gl.uniform2f(u.uDownbeat, motion.flash, motion.inversion);
    this.quad();
  }

  clear(value) {
    this.gl.clearColor(value, value, value, 1);
    this.gl.clear(this.gl.COLOR_BUFFER_BIT);
  }

  // An interrupted fade starts from the actual previous mix, before its effects.
  freeze() {
    const from = this.source;
    if (!from) return null;
    const frozen = this.target('frozen', from.width, from.height);
    this.blit(from, frozen.framebuffer, frozen.width, frozen.height);
    return frozen;
  }

  blit(from, framebuffer, width, height) {
    const gl = this.gl;
    gl.bindFramebuffer(gl.READ_FRAMEBUFFER, from.framebuffer);
    gl.bindFramebuffer(gl.DRAW_FRAMEBUFFER, framebuffer);
    gl.blitFramebuffer(0, 0, from.width, from.height, 0, 0, width, height, gl.COLOR_BUFFER_BIT, gl.LINEAR);
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
  }

  mix(from, scene, fraction) {
    const mixed = this.target('mixed', scene.width, scene.height);
    const u = this.use('mix');
    this.texture(0, u.uFrom, from);
    this.texture(1, u.uTo, scene);
    this.gl.uniform1f(u.uAmount, fraction);
    this.quad();
    return mixed;
  }

  blurred(source, name, radius, threshold, divisor) {
    const gl = this.gl;
    const width = Math.max(1, Math.floor(source.width / divisor));
    const height = Math.max(1, Math.floor(source.height / divisor));
    const pixels = 1 + radius * 36;
    const u = this.use('blur');
    const horizontal = this.target('horizontal', width, height);
    this.texture(0, u.uImage, source);
    gl.uniform2f(u.uStep, pixels / source.width, 0);
    gl.uniform1f(u.uThreshold, threshold);
    this.quad();
    const vertical = this.target(name, width, height);
    this.texture(0, u.uImage, horizontal);
    gl.uniform2f(u.uStep, 0, pixels / source.height);
    gl.uniform1f(u.uThreshold, -1);
    this.quad();
    return vertical;
  }

  present(source, fx, width, height) {
    const gl = this.gl;
    this.source = source;
    if (!fx.enabled || fx.strength <= 0 || (fx.blur <= 0 && fx.bloom <= 0)) {
      this.blit(source, null, width, height);
      return;
    }
    const divisor = [8, 4, 2][fx.quality] ?? 4;
    const blur = fx.blur > 0 ? this.blurred(source, 'blur', fx.blurRadius, -1, divisor) : source;
    const bloom =
      fx.bloom > 0 ? this.blurred(source, 'bloom', fx.bloomRadius, fx.threshold, divisor) : source;
    const final = this.target('final', source.width, source.height);
    const u = this.use('composite');
    this.texture(0, u.uImage, source);
    this.texture(1, u.uBlur, blur);
    this.texture(2, u.uBloom, bloom);
    gl.uniform1f(u.uBlurAmount, Math.min(1, fx.strength * fx.blur));
    gl.uniform1f(u.uBloomAmount, Math.min(2, fx.strength * fx.bloom));
    this.quad();
    this.blit(final, null, width, height);
  }

  dispose() {
    const gl = this.gl;
    for (const target of Object.values(this.targets)) target.release();
    for (const program of Object.values(this.programs)) gl.deleteProgram(program.id);
    if (this.buffer) gl.deleteBuffer(this.buffer);
    if (this.vao) gl.deleteVertexArray(this.vao);
  }
}
