package protocol

/**
 * A screen's remote state, held for as long as the bundle is loaded.
 *
 * One instance per screen id, so two screens with a variable of the same name
 * never see each other's value. Replacing the bundle discards all of it, which
 * is the behaviour a new implementation wants: state belongs to the code that
 * declared it.
 *
 * Typed accessors rather than a generic map because the compiler already knows
 * every declaration's type. A wrong type here means generated code and this
 * store disagree, so it fails rather than coercing.
 */
class ScreenState {

    private val values = HashMap<String, Any?>()

    /**
     * Seeds a declaration's initial value, once.
     *
     * Called at the top of every render, because render is also the first thing
     * that runs for a screen. Only the first call takes effect, so a re-render
     * after a button changed the value does not reset it.
     */
    fun initInt(name: String, initial: Int) = initialize(name, initial)

    fun initString(name: String, initial: String) = initialize(name, initial)

    fun initBoolean(name: String, initial: Boolean) = initialize(name, initial)

    fun int(name: String): Int = read(name) as? Int ?: wrongType(name, "Int")

    fun string(name: String): String = read(name) as? String ?: wrongType(name, "String")

    fun boolean(name: String): Boolean = read(name) as? Boolean ?: wrongType(name, "Boolean")

    fun long(name: String): Long = read(name) as? Long ?: wrongType(name, "Long")

    fun float(name: String): Float = read(name) as? Float ?: wrongType(name, "Float")

    fun double(name: String): Double = read(name) as? Double ?: wrongType(name, "Double")

    fun setInt(name: String, value: Int) {
        values[name] = value
    }

    fun setString(name: String, value: String) {
        values[name] = value
    }

    fun setBoolean(name: String, value: Boolean) {
        values[name] = value
    }

    private fun initialize(name: String, initial: Any?) {
        if (!values.containsKey(name)) values[name] = initial
    }

    private fun read(name: String): Any? {
        if (!values.containsKey(name)) {
            throw IllegalStateException("Dootah screen state '$name' was never initialised")
        }
        return values[name]
    }

    private fun wrongType(name: String, type: String): Nothing =
        throw IllegalStateException("Dootah screen state '$name' is not a $type")
}
