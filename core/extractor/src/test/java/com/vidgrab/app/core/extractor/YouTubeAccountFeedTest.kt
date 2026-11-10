package com.vidgrab.app.core.extractor

import com.vidgrab.app.core.model.SearchFilter
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
            """<script>ytcfg.set({"INNERTUBE_API_KEY":"key","INNERTUBE_CLIENT_VERSION":"2.20261106.00.00","VISITOR_DATA":"visitor","SESSION_INDEX":1});</script>""",
            defaults,
        )

        assertEquals("key", config?.apiKey)
        assertEquals("2.20261106.00.00", config?.clientVersion)
        assertEquals("visitor", config?.visitorData)
        assertEquals(1, config?.sessionIndex)
    }

    @Test
    fun parsesSignedInSearchResultsForEveryFilter() {
        val videos = parseYouTubeSearch(SEARCH, SearchFilter.VIDEOS)
        assertEquals(1, videos.items.size)
        assertEquals("https://www.youtube.com/watch?v=abc123", videos.items[0].url)
        assertEquals("Search video", videos.items[0].title)
        assertEquals("Search Channel", videos.items[0].uploader)
        assertEquals(125L, videos.items[0].durationSec)
        assertEquals(3400L, videos.items[0].viewCount)
        assertEquals(YouTubeSearchCursor("SEARCH_NEXT"), videos.nextPage)

        val channels = parseYouTubeSearch(SEARCH, SearchFilter.CHANNELS)
        assertEquals(1, channels.items.size)
        assertEquals("https://www.youtube.com/@searchchannel", channels.items[0].url)
        assertEquals("Search Channel", channels.items[0].title)
        assertEquals("https://yt3.ggpht.com/avatar=s88", channels.items[0].thumbnail)
        assertEquals(1200L, channels.items[0].subscriberCount)
        assertEquals(-1L, channels.items[0].streamCount)

        val playlists = parseYouTubeSearch(SEARCH, SearchFilter.PLAYLISTS)
        assertEquals(1, playlists.items.size)
        assertEquals("https://www.youtube.com/playlist?list=PL123", playlists.items[0].url)
        assertEquals("Search playlist", playlists.items[0].title)
        assertEquals("Playlist Channel", playlists.items[0].uploader)
        assertEquals("https://i.ytimg.com/vi/abc123/hq720.jpg", playlists.items[0].thumbnail)
        assertEquals(10L, playlists.items[0].streamCount)

        assertEquals(3, parseYouTubeSearch(SEARCH, SearchFilter.ALL).items.size)
    }

    @Test
    fun parsesRelatedItemsAndDropsTheCurrentVideo() {
        val related = parseYouTubeRelated(NEXT, "currentvid1")

        assertEquals(2, related.size)
        assertEquals("https://www.youtube.com/watch?v=relvideo001", related[0].url)
        assertEquals("Related one", related[0].title)
        assertEquals("Related Channel", related[0].uploader)
        assertEquals(245L, related[0].durationSec)
        assertEquals(2000L, related[0].viewCount)
        assertEquals("https://www.youtube.com/watch?v=relvideo002", related[1].url)
        assertEquals("Related two", related[1].title)
        assertTrue(related.none { it.url.endsWith("currentvid1") })
    }

    @Test
    fun aPageWithoutClientConfigFallsBack() {
        val defaults = YouTubeFeedConfig("fallback", "old", null, "en", "US")
        assertNull(parseYouTubeConfig("<html></html>", defaults))
    }

    private companion object {
        val SEARCH = """
            {
              "contents": {
                "twoColumnSearchResultsRenderer": {
                  "primaryContents": {
                    "sectionListRenderer": {
                      "contents": [
                        {
                          "itemSectionRenderer": {
                            "contents": [
                              {
                                "videoRenderer": {
                                  "videoId": "abc123",
                                  "title": {"runs": [{"text": "Search video"}]},
                                  "thumbnail": {"thumbnails": [{"url": "https://i.ytimg.com/vi/abc123/hqdefault.jpg", "width": 480, "height": 360}]},
                                  "ownerText": {
                                    "runs": [{
                                      "text": "Search Channel",
                                      "navigationEndpoint": {"browseEndpoint": {"canonicalBaseUrl": "/@searchchannel"}}
                                    }]
                                  },
                                  "lengthText": {"simpleText": "2:05"},
                                  "viewCountText": {"simpleText": "3.4K views"},
                                  "publishedTimeText": {"simpleText": "1 day ago"},
                                  "navigationEndpoint": {"commandMetadata": {"webCommandMetadata": {"url": "/watch?v=abc123"}}}
                                }
                              },
                              {
                                "channelRenderer": {
                                  "channelId": "UC123",
                                  "title": {"simpleText": "Search Channel"},
                                  "thumbnail": {"thumbnails": [{"url": "//yt3.ggpht.com/avatar=s88", "width": 88, "height": 88}]},
                                  "subscriberCountText": {"simpleText": "@searchchannel"},
                                  "videoCountText": {"simpleText": "1.2K subscribers"},
                                  "descriptionSnippet": {"runs": [{"text": "About the channel"}]},
                                  "navigationEndpoint": {
                                    "browseEndpoint": {"browseId": "UC123", "canonicalBaseUrl": "/@searchchannel"}
                                  }
                                }
                              },
                              {
                                "lockupViewModel": {
                                  "contentId": "PL123",
                                  "contentType": "LOCKUP_CONTENT_TYPE_PLAYLIST",
                                  "contentImage": {
                                    "collectionThumbnailViewModel": {
                                      "primaryThumbnail": {
                                        "thumbnailViewModel": {
                                          "image": {"sources": [{"url": "https://i.ytimg.com/vi/abc123/hq720.jpg", "width": 720, "height": 404}]},
                                          "overlays": [{
                                            "thumbnailOverlayBadgeViewModel": {
                                              "thumbnailBadges": [{"thumbnailBadgeViewModel": {"text": "10 videos"}}]
                                            }
                                          }]
                                        }
                                      }
                                    }
                                  },
                                  "metadata": {
                                    "lockupMetadataViewModel": {
                                      "title": {"content": "Search playlist"},
                                      "metadata": {
                                        "contentMetadataViewModel": {
                                          "metadataRows": [
                                            {"metadataParts": [{"text": {"content": "Playlist Channel"}}]},
                                            {"metadataParts": [{"text": {"content": "10 videos"}}]}
                                          ]
                                        }
                                      }
                                    }
                                  }
                                }
                              }
                            ]
                          }
                        },
                        {
                          "continuationItemRenderer": {
                            "continuationEndpoint": {"continuationCommand": {"token": "SEARCH_NEXT"}}
                          }
                        }
                      ]
                    }
                  }
                }
              }
            }
        """.trimIndent()

        val NEXT = """
            {
              "contents": {
                "twoColumnWatchNextResults": {
                  "secondaryResults": {
                    "secondaryResults": {
                      "results": [
                        {
                          "compactVideoRenderer": {
                            "videoId": "relvideo001",
                            "title": {"runs": [{"text": "Related one"}]},
                            "thumbnail": {"thumbnails": [{"url": "https://i.ytimg.com/vi/relvideo001/hqdefault.jpg", "width": 480, "height": 360}]},
                            "shortBylineText": {
                              "runs": [{
                                "text": "Related Channel",
                                "navigationEndpoint": {"browseEndpoint": {"canonicalBaseUrl": "/@related"}}
                              }]
                            },
                            "lengthText": {"simpleText": "4:05"},
                            "viewCountText": {"simpleText": "2K views"},
                            "publishedTimeText": {"simpleText": "3 days ago"},
                            "navigationEndpoint": {"commandMetadata": {"webCommandMetadata": {"url": "/watch?v=relvideo001"}}}
                          }
                        },
                        {
                          "lockupViewModel": {
                            "contentId": "relvideo002",
                            "contentType": "LOCKUP_CONTENT_TYPE_VIDEO",
                            "contentImage": {
                              "thumbnailViewModel": {
                                "image": {"sources": [{"url": "https://i.ytimg.com/vi/relvideo002/hqdefault.jpg", "width": 480, "height": 270}]},
                                "overlays": [{
                                  "thumbnailBottomOverlayViewModel": {
                                    "badges": [{"thumbnailBadgeViewModel": {"text": "4:33"}}]
                                  }
                                }]
                              }
                            },
                            "metadata": {
                              "lockupMetadataViewModel": {
                                "title": {"content": "Related two"},
                                "metadata": {
                                  "contentMetadataViewModel": {
                                    "metadataRows": [
                                      {"metadataParts": [{"text": {"content": "Second Channel"}}]},
                                      {"metadataParts": [{"text": {"content": "8K views"}}, {"text": {"content": "1 week ago"}}]}
                                    ]
                                  }
                                }
                              }
                            }
                          }
                        },
                        {
                          "compactVideoRenderer": {
                            "videoId": "currentvid1",
                            "title": {"runs": [{"text": "Current video"}]},
                            "navigationEndpoint": {"commandMetadata": {"webCommandMetadata": {"url": "/watch?v=currentvid1"}}}
                          }
                        }
                      ]
                    }
                  }
                }
              }
            }
        """.trimIndent()

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
