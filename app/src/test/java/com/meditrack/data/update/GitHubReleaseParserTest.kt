package com.meditrack.data.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The GitHub release payload, as this app actually receives it. */
class GitHubReleaseParserTest {

    private val release = """
        {
          "tag_name": "v1.8.0",
          "name": "药准时 1.8.0",
          "html_url": "https://github.com/ChenyangLT/meditrack/releases/tag/v1.8.0",
          "body": "# 药准时 1.8.0\n\n自动检查更新。",
          "published_at": "2026-10-03T02:00:00Z",
          "assets": [
            { "name": "MediTrack-release-1.8.0-20261003-0200.apk",
              "browser_download_url": "https://github.com/ChenyangLT/meditrack/releases/download/v1.8.0/MediTrack-release-1.8.0-20261003-0200.apk" },
            { "name": "checksums.txt", "browser_download_url": "https://example.invalid/checksums.txt" }
          ]
        }
    """.trimIndent()

    @Test
    fun `a release is read into the fields the dialog needs`() {
        val info = GitHubReleaseParser.parse(release)
        assertThat(info).isNotNull()
        requireNotNull(info)
        assertThat(info.version).isEqualTo("1.8.0")
        assertThat(info.tagName).isEqualTo("v1.8.0")
        assertThat(info.releaseUrl).contains("/releases/tag/v1.8.0")
        assertThat(info.body).contains("自动检查更新")
    }

    @Test
    fun `the apk asset is picked, not just any file`() {
        val info = GitHubReleaseParser.parse(release)
        assertThat(info!!.apkUrl).endsWith(".apk")
        assertThat(info.apkUrl).doesNotContain("checksums")
    }

    @Test
    fun `a release without an apk still has somewhere to send the user`() {
        val json = """{"tag_name":"v1.9.0","html_url":"https://github.com/ChenyangLT/meditrack/releases/tag/v1.9.0"}"""
        val info = GitHubReleaseParser.parse(json)
        assertThat(info!!.apkUrl).isNull()
        assertThat(info.releaseUrl).endsWith("/v1.9.0")
    }

    @Test
    fun `a release without a tag is not an update`() {
        assertThat(GitHubReleaseParser.parse("""{"name":"nothing"}""")).isNull()
        assertThat(GitHubReleaseParser.parse("""{"tag_name":"  "}""")).isNull()
    }

    @Test
    fun `malformed responses are ignored rather than thrown`() {
        // GitHub answers with HTML when rate-limited or when a proxy interferes.
        assertThat(GitHubReleaseParser.parse("not json at all")).isNull()
        assertThat(GitHubReleaseParser.parse("")).isNull()
        assertThat(GitHubReleaseParser.parse("null")).isNull()
    }

    @Test
    fun `a missing html_url falls back to the releases page`() {
        val info = GitHubReleaseParser.parse("""{"tag_name":"v2.0.0"}""")
        assertThat(info!!.releaseUrl).isEqualTo(GitHubReleaseParser.FALLBACK_URL)
    }

    // ------------------------------------------------- this project's own manifest (primary endpoint)

    private val manifest = """
        {
          "schemaVersion": 1,
          "version": "1.8.1",
          "tagName": "v1.8.1",
          "releaseUrl": "https://github.com/ChenyangLT/meditrack/releases/tag/v1.8.1",
          "apkUrl": "https://github.com/ChenyangLT/meditrack/releases/download/v1.8.1/MediTrack-release-1.8.1.apk",
          "body": "# 药准时 1.8.1"
        }
    """.trimIndent()

    @Test
    fun `the project's own manifest is read too`() {
        // The primary endpoint is this project's own site, not the API: api.github.com is unreliable on
        // some networks while the Pages CDN answers.
        val info = GitHubReleaseParser.parseManifest(manifest)
        assertThat(info).isNotNull()
        requireNotNull(info)
        assertThat(info.version).isEqualTo("1.8.1")
        assertThat(info.tagName).isEqualTo("v1.8.1")
        assertThat(info.apkUrl).endsWith(".apk")
        assertThat(info.releaseUrl).endsWith("/v1.8.1")
    }

    @Test
    fun `a manifest without a tag still names the release`() {
        val info = GitHubReleaseParser.parseManifest("""{"version":"1.9.0"}""")
        assertThat(info!!.tagName).isEqualTo("v1.9.0")
        assertThat(info.releaseUrl).isEqualTo(GitHubReleaseParser.FALLBACK_URL)
    }

    @Test
    fun `a manifest with no usable version is not an update`() {
        assertThat(GitHubReleaseParser.parseManifest("""{"schemaVersion":1}""")).isNull()
        assertThat(GitHubReleaseParser.parseManifest("""{"version":"  "}""")).isNull()
        assertThat(GitHubReleaseParser.parseManifest("<html>404</html>")).isNull()
    }

    @Test
    fun `the two endpoints describe different versions here, so a stale one is detectable`() {
        // If both endpoints ever disagreed in shape, the check would flap between "update" and
        // "latest"; this pins that each parser reads its own version field.
        val fromApi = GitHubReleaseParser.parse(release)!!
        val fromManifest = GitHubReleaseParser.parseManifest(manifest)!!
        assertThat(fromApi.version).isEqualTo("1.8.0")
        assertThat(fromManifest.version).isEqualTo("1.8.1")
    }
}
