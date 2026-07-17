package app.rebubble.data.remote

/** Loads a JSON fixture from `src/test/resources/fixtures/<name>` as a raw string. */
fun loadFixture(name: String): String {
    val stream = object {}.javaClass.classLoader!!.getResourceAsStream("fixtures/$name")
        ?: error("Fixture not found on classpath: fixtures/$name")
    return stream.bufferedReader().use { it.readText() }
}
