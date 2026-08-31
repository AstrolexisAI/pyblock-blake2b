package com.astrolexis.pyblock.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import com.astrolexis.pyblock.R

/** The 8-bit voice of the cabinet — a SoundPool one-shot bank mirroring the
 *  [Haptics] vocabulary. Respects the ringer mode (silent/vibrate → no sound)
 *  and a user toggle. Init once in MainActivity. Every call is fail-safe. */
object Sfx {
    private var pool: SoundPool? = null
    private var audio: AudioManager? = null
    private val ids = HashMap<String, Int>()
    var enabled: Boolean = true; private set
    private var prefs: android.content.SharedPreferences? = null

    fun init(ctx: Context) {
        val app = ctx.applicationContext
        audio = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        prefs = app.getSharedPreferences("pyblock_sfx", Context.MODE_PRIVATE)
        enabled = prefs?.getBoolean("enabled", true) ?: true
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        pool = SoundPool.Builder().setMaxStreams(6).setAudioAttributes(attrs).build()
        val map = mapOf(
            "blip" to R.raw.sfx_blip, "select" to R.raw.sfx_select, "tap" to R.raw.sfx_tap,
            "coin" to R.raw.sfx_coin, "powerup" to R.raw.sfx_powerup,
            "stageclear" to R.raw.sfx_stageclear, "boot" to R.raw.sfx_boot, "error" to R.raw.sfx_error,
        )
        map.forEach { (k, res) -> runCatching { ids[k] = pool!!.load(app, res, 1) } }
    }

    fun setEnabled(v: Boolean) { enabled = v; prefs?.edit()?.putBoolean("enabled", v)?.apply() }

    private fun play(key: String, vol: Float = 0.7f) {
        if (!enabled) return
        // Respect the ringer: on silent/vibrate the cabinet stays quiet.
        if (audio?.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        val id = ids[key] ?: return
        runCatching { pool?.play(id, vol, vol, 1, 0, 1f) }
    }

    fun tap() = play("tap", 0.5f)
    fun select() = play("select", 0.55f)
    fun blip() = play("blip", 0.55f)
    fun coin() = play("coin", 0.7f)
    fun powerUp() = play("powerup", 0.7f)
    fun stageClear() = play("stageclear", 0.85f)
    fun boot() = play("boot", 0.7f)
    fun error() = play("error", 0.7f)
    fun received() = coin()
    fun success() = powerUp()
}
