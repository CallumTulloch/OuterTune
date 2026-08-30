package com.dd3boh.outertune.ui.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeUtilsTest {
    @Test
    fun `large YouTube JPEG thumbnail uses max resolution and preserves query`() {
        val source = "https://i.ytimg.com/vi/y0mb2xBfRg8/sddefault.jpg?sqp=value&rs=signature"

        assertEquals(
            "https://i.ytimg.com/vi/y0mb2xBfRg8/maxresdefault.jpg?sqp=value&rs=signature",
            source.resize(1080, 1080)
        )
    }

    @Test
    fun `large YouTube WebP thumbnail uses max resolution`() {
        val source = "https://i.ytimg.com/vi_webp/y0mb2xBfRg8/sddefault.webp"

        assertEquals(
            "https://i.ytimg.com/vi_webp/y0mb2xBfRg8/maxresdefault.webp",
            source.resize(1080, 1080)
        )
    }

    @Test
    fun `small YouTube thumbnail keeps stored URL`() {
        val source = "https://i.ytimg.com/vi/y0mb2xBfRg8/sddefault.jpg"

        assertEquals(source, source.resize(320, 320))
    }

    @Test
    fun `Google Music thumbnail still uses requested dimensions`() {
        val source = "https://lh3.googleusercontent.com/example=w544-h544-l90-rj"

        assertEquals(
            "https://lh3.googleusercontent.com/example=w1080-h1080-p-l90-rj",
            source.resize(1080, 1080)
        )
    }

    @Test
    fun `YouTube Music album artwork uses requested dimensions`() {
        val source = "https://yt3.googleusercontent.com/example=w120-h120-l90-rj"

        assertEquals(
            "https://yt3.googleusercontent.com/example=w1080-h1080-p-l90-rj",
            source.resize(1080, 1080)
        )
    }
}
