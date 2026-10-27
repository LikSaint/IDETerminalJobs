package dev.jobbar

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.project.DumbAware
import com.intellij.util.concurrency.AppExecutorUtil
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Short, quiet chimes synthesized in code (no audio files). Failures to play are ignored. */
object JobSounds {
    enum class Kind(val notes: List<Pair<Double, Int>>) {
        // (frequency Hz, duration ms)
        SUCCESS(listOf(659.25 to 90, 880.0 to 150)),
        ERROR(listOf(440.0 to 120, 329.63 to 210)),
        FINISHED(listOf(783.99 to 160))
    }

    private const val KEY = "dev.jobbar.sounds"
    private const val SAMPLE_RATE = 44_100f
    private const val VOLUME = 0.12

    var enabled: Boolean
        get() = PropertiesComponent.getInstance().getBoolean(KEY, true)
        set(value) = PropertiesComponent.getInstance().setValue(KEY, value, true)

    fun play(kind: Kind) {
        if (!enabled) return
        AppExecutorUtil.getAppExecutorService().execute {
            runCatching {
                val pcm = synthesize(kind)
                val format = AudioFormat(SAMPLE_RATE, 16, 1, true, false)
                AudioSystem.getSourceDataLine(format).use { line ->
                    line.open(format)
                    line.start()
                    line.write(pcm, 0, pcm.size)
                    line.drain()
                }
            }
        }
    }

    private fun synthesize(kind: Kind): ByteArray {
        val samples = kind.notes.flatMap { (frequency, millis) ->
            val count = (SAMPLE_RATE * millis / 1000).toInt()
            (0 until count).map { i ->
                val t = i / SAMPLE_RATE
                // Quick attack, exponential decay: a soft "ding" without clicks.
                val envelope = minOf(1.0, i / (SAMPLE_RATE * 0.005)) * exp(-t * 18.0)
                VOLUME * envelope * sin(2 * PI * frequency * t)
            }
        }
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, value ->
            val sample = (value * Short.MAX_VALUE).toInt()
            bytes[i * 2] = (sample and 0xFF).toByte()
            bytes[i * 2 + 1] = (sample shr 8 and 0xFF).toByte()
        }
        return bytes
    }
}

class JobSoundsToggleAction : ToggleAction("Play Sounds", "Play a short sound when a job succeeds, fails or ends", null), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun isSelected(e: AnActionEvent): Boolean = JobSounds.enabled
    override fun setSelected(e: AnActionEvent, state: Boolean) {
        JobSounds.enabled = state
        if (state) JobSounds.play(JobSounds.Kind.SUCCESS)
    }
}
