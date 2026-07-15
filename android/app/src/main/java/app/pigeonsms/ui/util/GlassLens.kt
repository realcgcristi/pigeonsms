package app.pigeonsms.ui.util

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext

private object TiltHub {
    val state = mutableStateOf(Offset.Zero)
    private var manager: SensorManager? = null
    private var listener: SensorEventListener? = null
    private var refs = 0
    private var sx = 0f
    private var sy = 0f

    fun acquire(context: android.content.Context) {
        refs++
        if (listener != null) return
        val sm = context.applicationContext.getSystemService(SensorManager::class.java) ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val nx = (e.values[0] / 9.8f).coerceIn(-1f, 1f)
                val ny = (e.values[1] / 9.8f).coerceIn(-1f, 1f)
                sx += (nx - sx) * 0.15f
                sy += (ny - sy) * 0.15f
                state.value = Offset(-sx, sy)
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_GAME)
        manager = sm
        listener = l
    }

    fun release() {
        refs--
        if (refs <= 0) {
            listener?.let { manager?.unregisterListener(it) }
            listener = null
            manager = null
            refs = 0
        }
    }
}

@Composable
fun rememberTilt(): State<Offset> {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        TiltHub.acquire(context)
        onDispose { TiltHub.release() }
    }
    return TiltHub.state
}

private const val LENS_AGSL = """
uniform float2 size;
uniform float2 tilt;
uniform float radius;
uniform shader content;

half4 main(float2 coord) {
    float2 uv = coord / size;
    float2 d = min(uv, 1.0 - uv);
    float edge = min(d.x, d.y);
    float2 center = uv - 0.5;
    float bulge = smoothstep(0.14, 0.0, edge) * 0.10;
    float2 refr = coord - center * size * bulge;
    half4 src = content.eval(refr);
    float rim = smoothstep(0.09, 0.0, edge);
    float2 spot = float2(0.5 + tilt.x * 0.42, 0.34 + tilt.y * 0.42);
    float spec = smoothstep(0.55, 0.0, distance(uv, spot)) * 0.55;
    half3 lit = src.rgb + half3(1.0) * (rim * 0.45 + spec) * src.a;
    // rounded-box SDF mask so the effect never paints square corners
    float2 hp = size * 0.5;
    float2 q = abs(coord - hp) - (hp - radius);
    float sdf = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
    float mask = 1.0 - smoothstep(-1.0, 1.0, sdf);
    return half4(lit * mask, src.a * mask);
}
"""

fun Modifier.liquidLens(shape: Shape, tilt: State<Offset>): Modifier =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        this.clip(shape)
    } else {
        this.composed {
            val shader = remember { RuntimeShader(LENS_AGSL) }
            graphicsLayer {
                val t = tilt.value
                val w = size.width.coerceAtLeast(1f)
                val h = size.height.coerceAtLeast(1f)
                shader.setFloatUniform("size", w, h)
                shader.setFloatUniform("tilt", t.x, t.y)
                shader.setFloatUniform("radius", minOf(w, h) * 0.5f)
                renderEffect = RenderEffect
                    .createRuntimeShaderEffect(shader, "content")
                    .asComposeRenderEffect()
                this.clip = true
                this.shape = shape
            }
        }
    }
