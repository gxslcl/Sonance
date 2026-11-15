package android.net

import android.os.Parcel

/**
 * Minimal concrete [Uri] for JVM unit tests where Android's mockable [Uri.parse] returns null.
 */
internal class TestUri(private val raw: String) : Uri() {
    override fun isHierarchical(): Boolean = true
    override fun isRelative(): Boolean = false
    override fun getScheme(): String? = raw.substringBefore(':', missingDelimiterValue = "").ifEmpty { null }
    override fun getSchemeSpecificPart(): String = raw.substringAfter(':', raw)
    override fun getEncodedSchemeSpecificPart(): String = schemeSpecificPart
    override fun getAuthority(): String? = null
    override fun getEncodedAuthority(): String? = null
    override fun getUserInfo(): String? = null
    override fun getEncodedUserInfo(): String? = null
    override fun getHost(): String? = null
    override fun getPort(): Int = -1
    override fun getPath(): String? = raw
    override fun getEncodedPath(): String? = raw
    override fun getQuery(): String? = null
    override fun getEncodedQuery(): String? = null
    override fun getFragment(): String? = null
    override fun getEncodedFragment(): String? = null
    override fun getPathSegments(): MutableList<String> = mutableListOf()
    override fun getLastPathSegment(): String? = null
    override fun buildUpon(): Builder = throw UnsupportedOperationException()
    override fun describeContents(): Int = 0
    override fun writeToParcel(dest: Parcel, flags: Int) = Unit
    override fun toString(): String = raw
    override fun equals(other: Any?): Boolean = other is TestUri && other.raw == raw
    override fun hashCode(): Int = raw.hashCode()
}
