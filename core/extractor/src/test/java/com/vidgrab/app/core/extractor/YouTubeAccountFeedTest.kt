package com.vidgrab.app.core.extractor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeAccountFeedTest {
    @Test
    fun parsesModernAndClassicHomeItems() {
        val page = parseYouTubeHome(HOME)

        assertEquals(3, page.items.size)
        val classic = page.items[0]
        assertEquals("https://www.youtube.com/watch?v=abc123", classic.url)
        assertEquals("First video", classic.title)
        assertEquals("Channel One", classic.uploader)
        assertEquals("https://www.youtube.com/@one", classic.uploaderUrl)
        assertEquals(185L, classic.durationSec)
        assertEquals(1200L, classic.viewCount)
        assertEquals("2 days ago", classic.uploadDate)
        assertFalse(classic.isShort)

        val modern = page.items[1]
        assertEquals("https://www.youtube.com/watch?v=def456", modern.url)
        assertEquals("Second video", modern.title)
        assertEquals("Channel Two", modern.uploader)
        assertEquals("https://img/avatar.jpg", modern.uploaderAvatar)
        assertEquals(600L, modern.durationSec)
        assertEquals(5968L, modern.viewCount)
        assertEquals("10 hours ago", modern.uploadDate)
        assertFalse(modern.isShort)

        val short = page.items[2]
        assertEquals("https://www.youtube.com/shorts/short1", short.url)
        assertTrue(short.isShort)

        assertEquals(YouTubeFeedCursor("NEXT_TOKEN"), page.nextPage)
    }

    @Test
    fun readsTheClientVersionFromTheYouTubePage() {
        val defaults = YouTubeFeedConfig("fallback", "old", null, "en", "US")
        val config = parseYouTubeConfig(
            """<script>ytcfg.set({"INNERTUBE_API_KEY":"key","INNERTUBE_CLIENT_VERSION":"2.20261106.00.00","VISITOR_DATA":"visitor"});</script>""",
            defaults,
        )

        assertEquals("key", config?.apiKey)
        assertEquals("2.20261106.00.00", config?.clientVersion)
        assertEquals("visitor", config?.visitorData)
    }

    @Test
    fun aPageWithoutClientConfigFallsBack() {
        val defaults = YouTubeFeedConfig("fallback", "old", null, "en", "US")
        assertNull(parseYouTubeConfig("<html></html>", defaults))
    }

    private companion object {
        val HOME = """
            {
              "contents": {
                "richGridRenderer": {
                  "contents": [
                    {
                      "richItemRenderer": {
                        "content": {
                          "videoRenderer": {
                            "videoId": "abc123",
                            "title": {"runs": [{"text": "First video"}]},
                            "thumbnail": {"thumbnails": [{"url": "https://img/1.jpg", "width": 120, "height": 90}]},
                            "ownerText": {
                              "runs": [{
                                "text": "Channel One",
                                "navigationEndpoint": {"browseEndpoint": {"canonicalBaseUrl": "/@one"}}
                              }]
                            },
                            "lengthText": {"simpleText": "3:05"},
                            "viewCountText": {"simpleText": "1.2K views"},
                            "publishedTimeText": {"simpleText": "2 days ago"},
                            "navigationEndpoint": {"commandMetadata": {"webCommandMetadata": {"url": "/watch?v=abc123"}}}
                          }
                        }
                      }
                    },
                    {
                      "richItemRenderer": {
                        "content": {
                          "lockupViewModel": {
                            "contentId": "def456",
                            "contentType": "LOCKUP_CONTENT_TYPE_VIDEO",
                            "contentImage": {
                              "thumbnailViewModel": {
                                "image": {"sources": [{"url": "https://img/2.jpg", "width": 480, "height": 270}]},
                                "overlays": [{
                                  "thumbnailBottomOverlayViewModel": {
                                    "badges": [{"thumbnailBadgeViewModel": {"text": "10:00"}}]
                                  }
                                }]
                              }
                            },
                            "metadata": {
                              "lockupMetadataViewModel": {
                                "title": {"content": "Second video"},
                                "image": {
                                  "decoratedAvatarViewModel": {
                                    "avatar": {
                                      "avatarViewModel": {
                                        "image": {"sources": [{"url": "https://img/avatar.jpg"}]}
                                      }
                                    }
                                  }
                                },
                                "metadata": {
                                  "contentMetadataViewModel": {
                                    "metadataRows": [
                                      {"metadataParts": [{"text": {"content": "Channel Two"}}]},
                                      {
                                        "metadataParts": [
                                          {"text": {"content": "5,968 views"}},
                                          {"text": {"content": "10 hours ago"}}
                                        ]
                                      }
                                    ]
                                  }
                                }
                              }
                            }
                          }
                        }
                      }
                    },
                    {
                      "richItemRenderer": {
                        "content": {
                          "reelItemRenderer": {
                            "videoId": "short1",
                            "headline": {"simpleText": "A short"},
                            "thumbnail": {"thumbnails": [{"url": "https://img/3.jpg"}]},
                            "lengthText": {"simpleText": "0:20"},
                            "viewCountText": {"simpleText": "10K views"}
                          }
                        }
                      }
                    },
                    {
                      "continuationItemRenderer": {
                        "continuationEndpoint": {
                          "continuationCommand": {"token": "NEXT_TOKEN"}
                        }
                      }
                    }
                  ]
                }
              }
            }
        """.trimIndent()
    }
}
