package com.caleb.prism

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.io.IOException
import java.io.ByteArrayOutputStream

class SongServiceException(message: String, val retrySeconds: Int = 90, val needsKey: Boolean = false) : IOException(message)

/** Fixed HTTPS endpoints. Credentials are only sent in an AudD POST body and never logged. */
class SongServices {
    fun lookup(song: SongIdentity): CatalogSong? {
        val identified = song.spotifyId?.let { id ->
            parseTracks(request("https://api.reccobeats.com/v1/track?ids=${encode(id)}")).firstOrNull { it.song.spotifyId == id }
        }
        val match = identified ?: SongMatching.choose(song,
            parseTracks(request("https://api.reccobeats.com/v1/track/search?searchText=${encode(song.title.take(200))}"))) ?: return null
        val features = request("https://api.reccobeats.com/v1/audio-features?ids=${encode(match.catalogId)}").optJSONArray("content")
        val bpm = (0 until (features?.length() ?: 0)).mapNotNull { features?.optJSONObject(it) }
            .firstOrNull { it.optString("id") == match.catalogId }?.optDouble("tempo")
            ?.takeIf { it.isFinite() && it in 30.0..300.0 }
        return match.copy(bpm = bpm)
    }

    fun recognize(clip: RecognitionClip, token: String): RecognizedSong? {
        val boundary = "PrismAudioBoundary"
        val prefix = "--$boundary\r\nContent-Disposition: form-data; name=\"api_token\"\r\n\r\n$token\r\n" +
            "--$boundary\r\nContent-Disposition: form-data; name=\"return\"\r\n\r\nspotify\r\n" +
            "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"sample.wav\"\r\nContent-Type: audio/wav\r\n\r\n"
        val body = prefix.toByteArray() + clip.wav() + "\r\n--$boundary--\r\n".toByteArray()
        val json = request("https://api.audd.io/", body, "multipart/form-data; boundary=$boundary")
        if (json.optString("status") != "success") {
            val code = json.optJSONObject("error")?.optInt("error_code")
            throw SongServiceException(if (code in listOf(900, 901, 902)) "Check your AudD key and available credits." else "AudD couldn't identify this clip. Try again later.",
                retrySeconds = 300, needsKey = code in listOf(900, 901, 902))
        }
        return parseRecognition(json)
    }

    private fun request(url: String, body: ByteArray? = null, contentType: String? = null): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000; connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "Prism/1.7 (+https://github.com/xklob/prism)")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            if (code == 429) throw SongServiceException("Service is busy. Waiting before another request.",
                connection.getHeaderField("Retry-After")?.toIntOrNull()?.coerceIn(30, 3600) ?: 120)
            if (code in listOf(401, 403) && body != null) throw SongServiceException("Check your AudD key and available credits.", 300, true)
            if (code !in 200..299) throw SongServiceException("Song lookup is unavailable. Live timing still works.")
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (output.size() + read > 1_000_000) throw SongServiceException("Song service returned too much data.")
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            return JSONObject(bytes.toString(Charsets.UTF_8))
        } finally { connection.disconnect() }
    }

    companion object {
        private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
        fun parseTracks(json: JSONObject): List<CatalogSong> {
            val rows = json.optJSONArray("content") ?: return emptyList()
            return (0 until rows.length()).mapNotNull { index ->
                val row = rows.optJSONObject(index) ?: return@mapNotNull null
                val id = row.optString("id").takeIf { it.matches(Regex("[a-fA-F0-9-]{36}")) } ?: return@mapNotNull null
                val title = row.optString("trackTitle").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val artists = row.optJSONArray("artists")
                val artist = (0 until (artists?.length() ?: 0)).mapNotNull { artists?.optJSONObject(it)?.optString("name") }.joinToString(" / ")
                CatalogSong(SongIdentity(title.take(300), artist.take(300), row.optLong("durationMs").takeIf { it > 0 },
                    SongMatching.spotifyId(row.optString("href")), row.optionalString("isrc")), null, id)
            }
        }
        fun parseRecognition(json: JSONObject): RecognizedSong? {
            val result = json.optJSONObject("result") ?: return null
            val title = result.optionalString("title") ?: return null
            val spotify = result.optJSONObject("spotify")
            val id = spotify?.optionalString("id")?.takeIf { it.matches(Regex("[A-Za-z0-9]{22}")) }
            return RecognizedSong(SongIdentity(title.take(300), result.optString("artist").take(300),
                spotify?.optLong("duration_ms")?.takeIf { it > 0 }, id,
                spotify?.optJSONObject("external_ids")?.optionalString("isrc") ?: result.optionalString("isrc")),
                SongMatching.timecode(result.optionalString("timecode")))
        }
        private fun JSONObject.optionalString(key: String) = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
    }
}
