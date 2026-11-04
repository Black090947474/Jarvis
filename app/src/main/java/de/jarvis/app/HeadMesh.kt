package de.jarvis.app

import android.content.Context
import android.opengl.GLES20
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * Echter 3D-Kopf (Scan „Infinite“ von Lee Perry-Smith, Infinite-Realities, CC BY 3.0 – www.triplegangers.com).
 * Dunkler, glänzender Android-Look mit Netzlinien und Lichtkanten; die Augen leuchten beim Sprechen.
 * Format von assets/head.bin: int Vertices, int Indizes, float[3] Positionen, float[3] Normalen, ushort Indizes.
 */
class HeadMesh(ctx: Context) {
    private val vCount: Int
    private val iCount: Int
    private val pos: FloatBuffer
    private val nrm: FloatBuffer
    private val idx: ShortBuffer
    private var prog = 0
    private var aP = 0; private var aN = 0
    private var uJaw = 0
    private var uMvp = 0; private var uModel = 0; private var uEye = 0; private var uLevel = 0; private var uMode = 0; private var uT = 0; private var uFade = 0
    private val proj = FloatArray(16); private val view = FloatArray(16); private val model = FloatArray(16)
    private val vp = FloatArray(16); private val mvp = FloatArray(16)

    init {
        val bytes = ctx.assets.open("head.bin").use { it.readBytes() }
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        vCount = bb.int; iCount = bb.int
        fun floats(n: Int): FloatBuffer {
            val out = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            for (i in 0 until n) out.put(bb.float)
            out.position(0); return out
        }
        pos = floats(vCount * 3); nrm = floats(vCount * 3)
        idx = ByteBuffer.allocateDirect(iCount * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        for (i in 0 until iCount) idx.put(bb.short)
        idx.position(0)
    }

    fun create() {
        fun sh(type: Int, src: String) = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, src); GLES20.glCompileShader(it) }
        prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, sh(GLES20.GL_VERTEX_SHADER, VERT))
        GLES20.glAttachShader(prog, sh(GLES20.GL_FRAGMENT_SHADER, FRAG))
        GLES20.glLinkProgram(prog)
        aP = GLES20.glGetAttribLocation(prog, "aP"); aN = GLES20.glGetAttribLocation(prog, "aN")
        uMvp = GLES20.glGetUniformLocation(prog, "mvp"); uModel = GLES20.glGetUniformLocation(prog, "model")
        uEye = GLES20.glGetUniformLocation(prog, "eye"); uLevel = GLES20.glGetUniformLocation(prog, "level")
        uMode = GLES20.glGetUniformLocation(prog, "mode"); uT = GLES20.glGetUniformLocation(prog, "t")
        uFade = GLES20.glGetUniformLocation(prog, "fade"); uJaw = GLES20.glGetUniformLocation(prog, "jaw")
    }

    /** Zeichnet den Kopf. [yaw] Drehung, [level] 0..1 Sprech-/Hör-Lautstärke, [mode] 0 Ruhe 1 Zuhören 2 Denken 3 Sprechen. */
    fun draw(w: Int, h: Int, yaw: Float, t: Float, level: Float, mode: Int, fade: Float) {
        val aspect = w.toFloat() / h.coerceAtLeast(1)
        // Hochformat: so weit weg, dass Kopf + Schultern passen; Querformat: Kopf etwas größer
        val dist = if (aspect < 0.8f) 5.6f else 4.6f
        Matrix.perspectiveM(proj, 0, 30f, aspect, 0.1f, 50f)
        val ex = 0f; val ey = 0.38f; val ez = dist
        Matrix.setLookAtM(view, 0, ex, ey, ez, 0f, 0.14f, 0f, 0f, 1f, 0f)
        Matrix.setIdentityM(model, 0)
        Matrix.rotateM(model, 0, Math.toDegrees(yaw.toDouble()).toFloat(), 0f, 1f, 0f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(prog)
        GLES20.glEnableVertexAttribArray(aP); GLES20.glVertexAttribPointer(aP, 3, GLES20.GL_FLOAT, false, 0, pos)
        GLES20.glEnableVertexAttribArray(aN); GLES20.glVertexAttribPointer(aN, 3, GLES20.GL_FLOAT, false, 0, nrm)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES20.glUniform3f(uEye, ex, ey, ez)
        GLES20.glUniform1f(uLevel, level); GLES20.glUniform1f(uMode, mode.toFloat())
        GLES20.glUniform1f(uT, t); GLES20.glUniform1f(uFade, fade)
        GLES20.glUniform1f(uJaw, if (mode == 3) level * 0.05f else 0f)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, iCount, GLES20.GL_UNSIGNED_SHORT, idx)
        GLES20.glDisableVertexAttribArray(aN)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
    }

    companion object {
        private const val VERT = """
attribute vec3 aP; attribute vec3 aN;
uniform mat4 mvp; uniform mat4 model; uniform float jaw;
varying vec3 vP; varying vec3 vN; varying vec3 vW; varying float vOpen;
void main(){
  // Mund: Unterlippe + Kinn bewegen sich beim Sprechen nach unten
  vec3 p = aP;
  float w = smoothstep(0.25, 0.5, p.z) * smoothstep(0.34, 0.0, abs(p.x + 0.024)) * smoothstep(0.128, 0.102, p.y) * smoothstep(-0.42, -0.16, p.y);
  p.y -= jaw * w; p.z -= jaw * w * 0.35;
  // vOpen läuft über die gedehnten Dreiecke zwischen Ober- und Unterlippe von 0 nach 1 → dort dunkler Spalt
  vOpen = jaw > 0.001 ? smoothstep(0.128, 0.102, aP.y) * smoothstep(0.25, 0.5, aP.z) * smoothstep(0.2, 0.0, abs(aP.x + 0.024)) : -1.0;
  vP = aP;
  vW = (model * vec4(p, 1.0)).xyz;
  vN = normalize((model * vec4(aN, 0.0)).xyz);
  gl_Position = mvp * vec4(p, 1.0);
}"""

        private const val FRAG = """
precision mediump float;
uniform vec3 eye; uniform float level; uniform float mode; uniform float t; uniform float fade;
varying vec3 vP; varying vec3 vN; varying vec3 vW; varying float vOpen;
vec3 aces(vec3 x){ return clamp((x*(2.51*x+0.03))/(x*(2.43*x+0.59)+0.14), 0.0, 1.0); }
void main(){
  vec3 n = normalize(vN);
  vec3 v = normalize(eye - vW);
  vec3 cyan = vec3(0.0, 0.9, 1.0), violet = vec3(0.54, 0.36, 1.0), blue = vec3(0.24, 0.42, 1.0);
  vec3 base = vec3(0.025, 0.045, 0.10);
  // Lichter (wie im Vorschaubild): Hauptlicht, zwei farbige Kantenlichter, Licht von oben
  vec3 L1 = normalize(vec3(-2.0, 2.0, 3.0)), L2 = normalize(vec3(3.0, 1.0, -2.5)), L3 = normalize(vec3(-3.0, 1.5, -2.0)), L4 = normalize(vec3(0.0, 4.0, 0.5));
  vec3 col = base * 0.15;
  col += base * max(dot(n, L1), 0.0) * vec3(0.62, 0.77, 1.0) * 1.1;
  float sp1 = pow(max(dot(n, normalize(L1 + v)), 0.0), 60.0);
  float sp2 = pow(max(dot(n, normalize(L2 + v)), 0.0), 45.0);
  float sp3 = pow(max(dot(n, normalize(L3 + v)), 0.0), 45.0);
  float sp4 = pow(max(dot(n, normalize(L4 + v)), 0.0), 50.0);
  col += vec3(0.62, 0.77, 1.0) * sp1 * 0.8 + cyan * (sp2 * 1.1 + max(dot(n, L2), 0.0) * 0.04)
       + violet * (sp3 * 0.9 + max(dot(n, L3), 0.0) * 0.04) + blue * sp4 * 0.5;
  float fr = pow(1.0 - max(dot(n, v), 0.0), 4.0);
  // Netzlinien (Höhenlinien + Längslinien), am Rand stärker
  float l1 = smoothstep(0.93, 0.99, abs(fract(vP.y * 16.0) - 0.5) * 2.0);
  float l2 = smoothstep(0.94, 0.995, abs(fract(atan(vP.x, vP.z) * 10.0) - 0.5) * 2.0);
  float pulse = mode > 1.5 && mode < 2.5 ? 0.5 + 0.5 * sin(vP.y * 9.0 - t * 5.0) : 0.0;   // „Denken“: Lichtwellen
  float k = max(l1, l2 * 0.6) * (0.06 + 0.7 * fr + pulse * 0.5);
  float listen = mode > 0.5 && mode < 1.5 ? 0.35 + level : 0.0;
  vec3 rimC = mode > 1.5 && mode < 2.5 ? mix(cyan, violet, 0.75) : (mode > 2.5 ? mix(cyan, vec3(1.0), 0.25) : cyan);
  col += rimC * (k * 0.75 + fr * (0.5 + listen * 0.5));
  // Leuchtende Augen (geschlossene Lider glühen von innen) + Punkt auf der Stirn
  float speak = mode > 2.5 ? level : 0.0;
  float e = exp(-length(vP - vec3(-0.19, 0.40, 0.50)) * 26.0) + exp(-length(vP - vec3(0.14, 0.40, 0.50)) * 26.0);
  col += mix(cyan, vec3(1.0), 0.45) * e * (0.35 + 1.6 * speak + 0.4 * listen);
  col += violet * exp(-length(vP - vec3(-0.024, 0.78, 0.62)) * 30.0) * (0.6 + 0.3 * sin(t * 2.0));
  if (vOpen >= 0.0) col *= 1.0 - clamp(4.0 * vOpen * (1.0 - vOpen) * (0.25 + 0.6 * level), 0.0, 0.8);   // offener Mund = dunkler Spalt
  col = aces(col * 1.25);
  // unten weich ausblenden
  float a = smoothstep(-1.0, -0.35, vP.y) * fade;
  gl_FragColor = vec4(col * a, a);
}"""
    }
}
