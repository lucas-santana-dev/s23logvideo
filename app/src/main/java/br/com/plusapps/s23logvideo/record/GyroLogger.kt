package br.com.plusapps.s23logvideo.record

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock

/**
 * Giroscópio + acelerômetro durante a gravação, num .gcsv (formato de log do Gyroflow) para
 * estabilizar na pós. Os tempos são convertidos para o relógio dos quadros de vídeo
 * (System.nanoTime, TIMESTAMP_BASE_MONOTONIC) e gravados em relação ao 1º quadro do MP4.
 *
 * O SensorEvent.timestamp usa a base de SystemClock.elapsedRealtimeNanos; a diferença entre os dois
 * relógios (o tempo em deep sleep) é medida no início.
 */
class GyroLogger(context: Context) {
    private val sm = context.getSystemService(SensorManager::class.java)
    private val gyro: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED)
        ?: sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accel: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val thread = HandlerThread("gyro").apply { start() }

    /** (t_monotonic_ns, gx, gy, gz) e o último acelerômetro, em m/s². */
    private val samples = ArrayList<FloatArray>(200 * 60 * 12)
    private val times = ArrayList<Long>(200 * 60 * 12)
    private val lastAccel = FloatArray(3)
    private var clockOffsetNs = 0L

    val available get() = gyro != null
    val sampleCount get() = synchronized(samples) { samples.size }
    val sensorName get() = gyro?.name

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            when (e.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> System.arraycopy(e.values, 0, lastAccel, 0, 3)
                else -> synchronized(samples) {
                    times += e.timestamp - clockOffsetNs
                    samples += floatArrayOf(e.values[0], e.values[1], e.values[2], lastAccel[0], lastAccel[1], lastAccel[2])
                }
            }
        }

        override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
    }

    fun start() {
        val g = gyro ?: return
        clockOffsetNs = SystemClock.elapsedRealtimeNanos() - System.nanoTime()
        val h = Handler(thread.looper)
        sm.registerListener(listener, g, SensorManager.SENSOR_DELAY_FASTEST, h)
        accel?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_FASTEST, h) }
    }

    fun stop() {
        sm.unregisterListener(listener)
        thread.quitSafely()
    }

    /**
     * Texto .gcsv. [firstVideoPtsUs] = PTS (µs, relógio monotônico) do 1º quadro do arquivo.
     * [readoutMs] = tempo de leitura do rolling shutter (SENSOR_ROLLING_SHUTTER_SKEW).
     */
    fun toGcsv(videoFile: String, firstVideoPtsUs: Long, readoutMs: Double?, focalMm: Float?, lensLabel: String): String {
        val sb = StringBuilder(64 * (sampleCount + 32))
        sb.append("GYROFLOW IMU LOG\n")
        sb.append("version,1.3\n")
        sb.append("id,logcam_s23\n")
        // Eixos do Android em retrato (X à direita, Y para cima, Z para fora da tela). Se a estabilização
        // sair girada no Gyroflow, ajustar a orientação do IMU lá (ver docs/fase5.md).
        sb.append("orientation,XYZ\n")
        sb.append("note,LogCam S23 ${gyro?.name ?: ""}\n")
        sb.append("fwversion,LogCam\n")
        sb.append("timestamp,${System.currentTimeMillis() / 1000}\n")
        sb.append("vendor,LogCam\n")
        sb.append("videofilename,$videoFile\n")
        sb.append("lensprofile,\n")
        sb.append("lens_info,$lensLabel${focalMm?.let { " ${it}mm" } ?: ""}\n")
        readoutMs?.let { sb.append("frame_readout_time,%.3f\n".format(java.util.Locale.US, it)) }
        sb.append("frame_readout_direction,0\n")
        // t em µs; giroscópio em rad/s; acelerômetro em g.
        sb.append("tscale,0.000001\n")
        sb.append("gscale,1.0\n")
        sb.append("ascale,1.0\n")
        sb.append("t,gx,gy,gz,ax,ay,az\n")
        val g = SensorManager.GRAVITY_EARTH
        synchronized(samples) {
            for (i in samples.indices) {
                val t = times[i] / 1_000 - firstVideoPtsUs
                val v = samples[i]
                sb.append(t).append(',')
                    .append(fmt(v[0])).append(',').append(fmt(v[1])).append(',').append(fmt(v[2])).append(',')
                    .append(fmt(v[3] / g)).append(',').append(fmt(v[4] / g)).append(',').append(fmt(v[5] / g)).append('\n')
            }
        }
        return sb.toString()
    }

    /** Float.toString é rápido e o Gyroflow aceita notação científica (1.0E-5). */
    private fun fmt(f: Float) = f.toString()
}
