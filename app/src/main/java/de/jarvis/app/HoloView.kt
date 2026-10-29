package de.jarvis.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.view.MotionEvent
import de.jarvis.app.logic.HoloTopics
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs

/**
 * Echtes 3D-Hologramm (läuft komplett auf dem Handy, OpenGL ES 2.0):
 * Die Modelle (Jarvis-Kopf, Dinos, Planeten …) sind als Formeln beschrieben und werden per
 * „Raymarching“ live berechnet – mit Licht, leuchtenden Kanten, Scan-Linien und Funkeln.
 * Mit dem Finger drehen, antippen = [onTap].
 */
@SuppressLint("ViewConstructor")
class HoloView(context: Context) : GLSurfaceView(context) {
    @Volatile var scene = HoloTopics.Scene.HEAD; private set
    @Volatile private var pending: HoloTopics.Scene? = null
    @Volatile var mode = 0          // 0 Ruhe, 1 Zuhören, 2 Denken, 3 Sprechen
    @Volatile var level = 0f        // Lautstärke 0..1
    var onTap: (() -> Unit)? = null
    /** Liefert die aktuelle Mikrofon-Lautstärke (wird jedes Bild abgefragt). */
    var levelSource: (() -> Float)? = null
    @Volatile private var dragYaw = 0f
    @Volatile private var fade = 1f
    @Volatile private var fadeDir = 0
    private val start = SystemClock.elapsedRealtime()

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderOnTop(true)
        setRenderer(R())
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    /** Neues Modell zeigen (mit kurzem Aus- und Einblenden). */
    fun show(s: HoloTopics.Scene) {
        if (s == scene && pending == null) return
        pending = s; fadeDir = -1
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Halbe Auflösung reicht für ein weiches Hologramm und spart Akku
        if (w > 0 && h > 0) holder.setFixedSize((w * 0.55f).toInt(), (h * 0.55f).toInt())
    }

    private var downX = 0f; private var downY = 0f; private var lastX = 0f; private var moved = false
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; lastX = e.x; moved = false }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.x - downX) > 12 || abs(e.y - downY) > 12) moved = true
                dragYaw += (e.x - lastX) / width * 4f; lastX = e.x
            }
            MotionEvent.ACTION_UP -> if (!moved) onTap?.invoke()
        }
        return true
    }

    private inner class R : Renderer {
        private var prog = 0
        private lateinit var quad: FloatBuffer
        private var uRes = 0; private var uScene = 0; private var uT = 0; private var uYaw = 0; private var uPitch = 0
        private var uCam = 0; private var uMode = 0; private var uLevel = 0; private var uFade = 0
        private var w = 1; private var h = 1
        private var lastFrame = 0L

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            fun sh(type: Int, src: String) = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, src); GLES20.glCompileShader(it) }
            prog = GLES20.glCreateProgram()
            GLES20.glAttachShader(prog, sh(GLES20.GL_VERTEX_SHADER, VERT))
            GLES20.glAttachShader(prog, sh(GLES20.GL_FRAGMENT_SHADER, FRAG))
            GLES20.glLinkProgram(prog)
            quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
            uRes = GLES20.glGetUniformLocation(prog, "res"); uScene = GLES20.glGetUniformLocation(prog, "scene")
            uT = GLES20.glGetUniformLocation(prog, "t"); uYaw = GLES20.glGetUniformLocation(prog, "yaw")
            uPitch = GLES20.glGetUniformLocation(prog, "pitch"); uCam = GLES20.glGetUniformLocation(prog, "cam")
            uMode = GLES20.glGetUniformLocation(prog, "mode"); uLevel = GLES20.glGetUniformLocation(prog, "level")
            uFade = GLES20.glGetUniformLocation(prog, "fade")
            GLES20.glClearColor(0f, 0f, 0f, 0f)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) { w = width; h = height; GLES20.glViewport(0, 0, w, h) }

        override fun onDrawFrame(gl: GL10?) {
            // ~30 Bilder/s genügen
            val now = SystemClock.elapsedRealtime()
            val wait = 33 - (now - lastFrame)
            if (wait > 0) try { Thread.sleep(wait) } catch (_: InterruptedException) {}
            lastFrame = SystemClock.elapsedRealtime()
            if (fadeDir < 0) { fade -= 0.12f; if (fade <= 0f) { fade = 0f; pending?.let { scene = it }; pending = null; fadeDir = 1 } }
            else if (fadeDir > 0) { fade += 0.08f; if (fade >= 1f) { fade = 1f; fadeDir = 0 } }
            val t = (SystemClock.elapsedRealtime() - start) / 1000f
            val s = scene
            val head = s == HoloTopics.Scene.HEAD
            val yaw = if (head) 0.35f + kotlin.math.sin(t * 0.4f) * 0.22f + dragYaw else -0.5f + t * 0.35f + dragYaw
            val cam = CAMS[s.id]
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(prog)
            val loc = GLES20.glGetAttribLocation(prog, "p")
            GLES20.glEnableVertexAttribArray(loc)
            GLES20.glVertexAttribPointer(loc, 2, GLES20.GL_FLOAT, false, 0, quad)
            GLES20.glUniform2f(uRes, w.toFloat(), h.toFloat())
            GLES20.glUniform1f(uScene, s.id.toFloat())
            GLES20.glUniform1f(uT, t)
            GLES20.glUniform1f(uYaw, yaw)
            GLES20.glUniform1f(uPitch, if (head) 0.04f else 0.14f)
            GLES20.glUniform3f(uCam, cam[0], cam[1], cam[2])
            GLES20.glUniform1f(uMode, mode.toFloat())
            val lv = if (mode == 3) 0.45f + 0.4f * abs(kotlin.math.sin(t * 7.5f)) * (0.6f + 0.4f * kotlin.math.sin(t * 1.9f))
                     else (levelSource?.invoke() ?: level)
            GLES20.glUniform1f(uLevel, lv)
            GLES20.glUniform1f(uFade, fade)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
    }

    companion object {
        /** Kamera je Szene: x, y, Abstand. */
        private val CAMS = arrayOf(
            floatArrayOf(0f, -0.18f, 2.6f),   // Kopf
            floatArrayOf(0.45f, 1.4f, 9.5f),  // Brachiosaurus
            floatArrayOf(0.4f, 0.88f, 7.0f),  // T-Rex
            floatArrayOf(0.2f, 0.69f, 6.0f),  // Triceratops
            floatArrayOf(0f, -0.19f, 6.0f),   // Saturn
            floatArrayOf(0f, -0.14f, 4.4f),   // Erde
            floatArrayOf(0f, 0.06f, 5.0f),    // Rakete
            floatArrayOf(0f, 0.35f, 4.2f),    // Herz
            floatArrayOf(0f, 0.68f, 5.5f),    // Vulkan
            floatArrayOf(0f, -0.14f, 4.6f),   // Atom
        )

        private const val VERT = "attribute vec2 p; void main(){ gl_Position = vec4(p, 0.0, 1.0); }"

        private val FRAG = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
uniform vec2 res; uniform float scene; uniform float t; uniform float yaw; uniform float pitch;
uniform vec3 cam; uniform float mode; uniform float level; uniform float fade;
float smin(float a,float b,float k){float h=clamp(.5+.5*(b-a)/k,0.,1.);return mix(b,a,h)-k*h*(1.-h);}
float smax(float a,float b,float k){return -smin(-a,-b,k);}
float ell(vec3 p,vec3 r){float k0=length(p/r);float k1=length(p/(r*r));return k0*(k0-1.)/k1;}
float cap(vec3 p,vec3 a,vec3 b,float r){vec3 pa=p-a,ba=b-a;float h=clamp(dot(pa,ba)/dot(ba,ba),0.,1.);return length(pa-ba*h)-r;}
float rcone(vec3 p,vec3 a,vec3 b,float r1,float r2){vec3 pa=p-a,ba=b-a;float h=clamp(dot(pa,ba)/dot(ba,ba),0.,1.);return length(pa-ba*h)-mix(r1,r2,h);}
float torus(vec3 p,float R,float r){vec2 q=vec2(length(p.xz)-R,p.y);return length(q)-r;}
mat2 rot(float a){float c=cos(a),s=sin(a);return mat2(c,-s,s,c);}

float head(vec3 p){
  vec3 q=vec3(abs(p.x),p.y,p.z);
  float d=ell(p-vec3(0.,.16,-.06),vec3(.35,.41,.43));
  d=smin(d,ell(p-vec3(0.,-.12,.07),vec3(.27,.33,.31)),.14);
  d=smin(d,ell(p-vec3(0.,-.38,.17),vec3(.12,.09,.10)),.10);
  d=smin(d,ell(q-vec3(.19,-.02,.19),vec3(.10,.08,.09)),.08);
  d=smin(d,cap(p,vec3(-.17,.13,.29),vec3(.17,.13,.29),.045),.07);
  d=smax(d,-(length(q-vec3(.12,.045,.38))-.08),.05);
  d=smin(d,length(q-vec3(.12,.04,.285))-.06,.02);
  d=smin(d,rcone(p,vec3(0.,.09,.35),vec3(0.,-.10,.47),.035,.052),.04);
  d=smin(d,ell(p-vec3(0.,-.115,.435),vec3(.06,.042,.05)),.035);
  d=smin(d,ell(q-vec3(.035,-.115,.40),vec3(.04,.03,.04)),.03);
  float mo=.006+level*.03*step(2.5,mode);
  d=smin(d,ell(p-vec3(0.,-.205,.355),vec3(.085,.026,.045)),.035);
  d=smin(d,ell(p-vec3(0.,-.255-mo,.345),vec3(.075,.028,.045)),.035);
  d=smax(d,-ell(p-vec3(0.,-.232-mo*.5,.40),vec3(.07,mo,.04)),.01);
  d=smin(d,ell(q-vec3(.355,.0,-.02),vec3(.045,.115,.075)),.04);
  d=smin(d,cap(p,vec3(0.,-.32,-.06),vec3(0.,-.95,-.10),.165),.12);
  d=smin(d,ell(p-vec3(0.,-1.05,-.08),vec3(.62,.16,.26)),.18);
  return max(d,-(p.y+1.02));
}
float brachio(vec3 p){
  float s=sin(t*2.2)*.07;
  float d=ell(p,vec3(1.0,.52,.46));
  d=smin(d,ell(p-vec3(-.35,.18,0.),vec3(.6,.5,.42)),.2);
  d=smin(d,rcone(p,vec3(-.6,.3,0.),vec3(-1.1,1.2,0.),.34,.17),.2);
  d=smin(d,rcone(p,vec3(-1.1,1.2,0.),vec3(-1.30,1.62,0.),.15,.10),.08);
  d=smin(d,ell(p-vec3(-1.42,1.68,0.),vec3(.20,.10,.10)),.08);
  d=smin(d,ell(p-vec3(-1.33,1.76,0.),vec3(.10,.07,.08)),.05);
  d=smin(d,rcone(p,vec3(.75,.05,0.),vec3(1.65,-.22,.15+s),.32,.12),.15);
  d=smin(d,rcone(p,vec3(1.65,-.22,.15+s),vec3(2.5,-.30,.55+s*2.),.12,.015),.08);
  d=smin(d,rcone(p,vec3(-.55,-.15,.24),vec3(-.6+s,-1.15,.27),.19,.14),.12);
  d=smin(d,rcone(p,vec3(-.55,-.15,-.24),vec3(-.6-s,-1.15,-.27),.19,.14),.12);
  d=smin(d,rcone(p,vec3(.55,-.12,.22),vec3(.62-s,-1.15,.25),.24,.15),.12);
  d=smin(d,rcone(p,vec3(.55,-.12,-.22),vec3(.62+s,-1.15,-.25),.24,.15),.12);
  return d;
}
float trex(vec3 p){
  vec3 q=vec3(p.x,p.y,abs(p.z));
  float jaw=.04+.04*sin(t*1.5);
  float d=ell(p-vec3(0.,.12,0.),vec3(.78,.42,.38));
  d=smin(d,rcone(p,vec3(-.5,.3,0.),vec3(-.82,.58,0.),.26,.2),.15);
  d=smin(d,ell(p-vec3(-1.06,.66,0.),vec3(.36,.19,.18)),.1);
  d=smin(d,ell(p-vec3(-1.32,.6,0.),vec3(.2,.12,.14)),.08);
  d=smin(d,ell(p-vec3(-1.12,.46-jaw,0.),vec3(.32,.07,.14)),.05);
  d=smax(d,-ell(p-vec3(-1.25,.53-jaw*.5,0.),vec3(.26,jaw*.7,.2)),.02);
  d=smin(d,rcone(p,vec3(.6,.12,0.),vec3(1.5,0.,.1*sin(t)),.3,.12),.15);
  d=smin(d,rcone(p,vec3(1.5,0.,.1*sin(t)),vec3(2.35,.12,.3*sin(t)),.12,.02),.08);
  d=smin(d,ell(q-vec3(.12,-.12,.26),vec3(.26,.34,.15)),.12);
  d=smin(d,rcone(q,vec3(.18,-.36,.28),vec3(.04,-.98,.28),.12,.08),.08);
  d=smin(d,ell(q-vec3(-.08,-1.0,.28),vec3(.2,.05,.1)),.04);
  d=smin(d,rcone(q,vec3(-.52,.05,.22),vec3(-.66,-.12,.25),.05,.03),.04);
  return d;
}
float tricera(vec3 p){
  vec3 q=vec3(p.x,p.y,abs(p.z));
  float d=ell(p,vec3(.85,.45,.42));
  d=smin(d,ell(p-vec3(-.95,.05,0.),vec3(.32,.25,.22)),.15);
  d=smin(d,ell(p-vec3(-1.22,-.02,0.),vec3(.16,.13,.12)),.08);
  vec3 f=p-vec3(-.8,.24,0.); f.xy=rot(.55)*f.xy;
  d=smin(d,ell(f,vec3(.07,.38,.46)),.08);
  d=smin(d,rcone(q,vec3(-1.02,.24,.12),vec3(-1.5,.56,.16),.055,.006),.03);
  d=smin(d,rcone(p,vec3(-1.25,.06,0.),vec3(-1.4,.24,0.),.045,.005),.02);
  d=smin(d,rcone(q,vec3(-.5,-.15,.24),vec3(-.52,-.92,.27),.15,.12),.1);
  d=smin(d,rcone(q,vec3(.5,-.15,.24),vec3(.52,-.92,.27),.17,.13),.1);
  d=smin(d,rcone(p,vec3(.75,0.,0.),vec3(1.55,-.25,.12*sin(t)),.22,.02),.1);
  return d;
}
float saturn(vec3 p){
  float d=length(p)-.75;
  vec3 r=p; r.yz=rot(.42)*r.yz;
  float ring=max(abs(length(r.xz)-1.35)-.36,abs(r.y)-.012);
  ring=max(ring,-(abs(length(r.xz)-1.2)-.03));
  return min(d,ring);
}
float earth(vec3 p){ return length(p)-.95; }
float rocket(vec3 p){
  float d=rcone(p,vec3(0.,-.8,0.),vec3(0.,.55,0.),.27,.25);
  d=smin(d,rcone(p,vec3(0.,.55,0.),vec3(0.,1.15,0.),.25,.0),.05);
  float a=atan(p.z,p.x); float sec=6.2832/3.; a=mod(a+sec*.5,sec)-sec*.5;
  vec3 f=vec3(cos(a),p.y,sin(a))*vec3(length(p.xz),1.,length(p.xz));
  vec3 fq=abs(f-vec3(.38,-.68,0.))-vec3(.16,.24,.012);
  d=smin(d,length(max(fq,0.))+min(max(fq.x,max(fq.y,fq.z)),0.)-.01,.04);
  d=smax(d,-(length(p-vec3(0.,.25,.27))-.09),.02);
  float fl=ell(p-vec3(0.,-1.02-.06*sin(t*14.),0.),vec3(.14,.26+.05*sin(t*11.),.14));
  return min(d,fl);
}
float heart(vec3 p){
  float b=1.+.07*pow(abs(sin(t*2.6)),8.);
  p/=b;
  vec3 q=vec3(abs(p.x),p.y,p.z);
  float d=ell(q-vec3(.3,.25,0.),vec3(.42,.42,.34));
  d=smin(d,rcone(q,vec3(.28,.12,0.),vec3(0.,-.78,0.),.38,.04),.15);
  d=smin(d,rcone(p,vec3(.05,.45,0.),vec3(.08,.82,0.),.14,.11),.08);
  d=smin(d,rcone(p,vec3(.08,.82,0.),vec3(-.28,.78,0.),.11,.08),.05);
  return d*b;
}
float volcano(vec3 p){
  float d=rcone(p,vec3(0.,-.8,0.),vec3(0.,.35,0.),1.3,.38);
  d=max(d,-(p.y+.8));
  d=smax(d,-(length(p-vec3(0.,.6,0.))-.42),.06);
  for(int i=0;i<4;i++){float fi=float(i);float ph=fract(t*.18+fi*.25);
    d=smin(d,length(p-vec3(.15*sin(fi*2.3+t*.4),.45+ph*1.4,.1*cos(fi*1.7)))-(.12+ph*.22),.12);}
  return d;
}
float atom(vec3 p){
  float d=1e3;
  d=min(d,length(p-vec3(.08,.06,0.))-.13); d=min(d,length(p-vec3(-.08,.04,.06))-.13);
  d=min(d,length(p-vec3(0.,-.08,-.05))-.13); d=min(d,length(p-vec3(.02,.1,-.08))-.12);
  for(int i=0;i<3;i++){float fi=float(i);vec3 r=p; r.xy=rot(fi*1.047)*r.xy; r.yz=rot(1.2)*r.yz;
    d=min(d,torus(r,1.0,.012));
    float a=t*(1.4+fi*.3)+fi*2.;
    d=min(d,length(r-vec3(cos(a),0.,sin(a)))-.07);}
  return d;
}
float sdf(vec3 p){
  p.xz=rot(yaw)*p.xz; p.yz=rot(pitch)*p.yz;
  if(scene<.5) return head(p);
  if(scene<1.5) return brachio(p);
  if(scene<2.5) return trex(p);
  if(scene<3.5) return tricera(p);
  if(scene<4.5) return saturn(p);
  if(scene<5.5) return earth(p);
  if(scene<6.5) return rocket(p);
  if(scene<7.5) return heart(p);
  if(scene<8.5) return volcano(p);
  return atom(p);
}
vec3 objp(vec3 p){p.xz=rot(yaw)*p.xz;p.yz=rot(pitch)*p.yz;return p;}
vec3 nrm(vec3 p){vec2 e=vec2(.002,0.);return normalize(vec3(sdf(p+e.xyy)-sdf(p-e.xyy),sdf(p+e.yxy)-sdf(p-e.yxy),sdf(p+e.yyx)-sdf(p-e.yyx)));}
float hash(vec3 p){return fract(sin(dot(p,vec3(12.9898,78.233,37.719)))*43758.5453);}
void main(){
  vec2 uv=(gl_FragCoord.xy-.5*res)/res.y;
  vec3 ro=vec3(cam.x,cam.y,cam.z), rd=normalize(vec3(uv,-1.6));
  float tt=0.,d=1.; bool hit=false; float glow=0.;
  for(int i=0;i<96;i++){vec3 p=ro+rd*tt;d=sdf(p);glow+=.012/(.02+abs(d)*12.);if(d<.0015){hit=true;break;}tt+=d*.85;if(tt>14.)break;}
  vec3 cyan=vec3(.25,.85,1.), white=vec3(.85,.98,1.);
  float speak=step(2.5,mode)*level;
  vec3 col=cyan*glow*(.09+speak*.08);
  if(hit){
    vec3 p=ro+rd*tt; vec3 n=nrm(p); vec3 o=objp(p);
    float fr=pow(1.-max(dot(n,-rd),0.),2.2);
    bool isRound=scene>3.5&&scene<5.5;
    float k=scene<.5?75.:(isRound?18.:24.);
    float lines=smoothstep(.965,1.,abs(sin(o.y*k)));
    float vl=smoothstep(.95,1.,abs(sin(atan(o.z,o.x)*(isRound?9.:24.))))*(isRound?.7:.15);
    float dots=step(.993,hash(floor(o*(scene<.5?160.:70.))))*(.4+fr)*(.6+.4*sin(t*3.+o.x*40.));
    float diff=max(dot(n,normalize(vec3(.4,.6,.7))),0.);
    float spd=mode>1.5&&mode<2.5?1.6:.55;
    float band=exp(-pow((o.y-(fract(t*spd*.25)*3.-1.5))*7.,2.));
    col=cyan*(.06+.22*diff)+white*fr*(1.25+speak*.8)+cyan*lines*.65+cyan*vl*.4+white*dots*.8+white*band*.3;
    col+=cyan*glow*.05;
  }
  if(scene>.5){
    // Projektor: Lichtkegel und leuchtender Ring unten
    float by=-.43; float y=uv.y-by;
    if(y>0.){float hw=.16+y*.55; col+=cyan*(1.-smoothstep(hw*.75,hw,abs(uv.x)))*(1.-smoothstep(0.,.95,y))*.10;}
    float e=length(vec2(uv.x/.24,(uv.y-by)/.032));
    col+=cyan*exp(-pow((e-1.)*9.,2.))*1.1+cyan*(1.-smoothstep(0.,.75,e))*.35;
  }
  col*=.85+.15*sin(gl_FragCoord.y*1.6);
  col*=fade*(.94+.06*sin(t*31.));
  col=clamp(col,0.,1.);
  gl_FragColor=vec4(col,max(col.r,max(col.g,col.b)));
}
"""
    }
}
