package com.dd3boh.outertune.extensions

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

class PlayerExtTest {
    @Test
    fun `play button restarts an ended item even when playWhenReady stayed true`() {
        for (playWhenReady in listOf(false, true)) {
            val calls = mutableListOf<String>()
            player(Player.STATE_ENDED, playWhenReady, calls).togglePlayPause()
            assertEquals(listOf("seekToDefaultPosition", "prepare", "play"), calls)
        }
    }

    @Test
    fun `manual play prepares an idle player after a stopped error`() {
        val calls = mutableListOf<String>()
        player(Player.STATE_IDLE, false, calls).togglePlayPause()
        assertEquals(listOf("prepare", "setPlayWhenReady:true"), calls)
    }

    @Test
    fun `pause cancels waiting playback without preparing again`() {
        val calls = mutableListOf<String>()
        player(Player.STATE_IDLE, true, calls).togglePlayPause()
        assertEquals(listOf("setPlayWhenReady:false"), calls)
    }

    @Test
    fun `ordinary pause and resume retain current position`() {
        val calls = mutableListOf<String>()
        player(Player.STATE_READY, true, calls).togglePlayPause()
        player(Player.STATE_READY, false, calls).togglePlayPause()
        assertEquals(listOf("setPlayWhenReady:false", "setPlayWhenReady:true"), calls)
    }

    private fun player(state: Int, playWhenReady: Boolean, calls: MutableList<String>): Player =
        Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "getPlaybackState" -> state
                "getPlayWhenReady" -> playWhenReady
                "setPlayWhenReady" -> { calls += "setPlayWhenReady:${args!!.single()}"; null }
                "seekToDefaultPosition", "prepare", "play" -> { calls += method.name; null }
                else -> error("Unexpected player call ${method.name}")
            }
        } as Player
}
