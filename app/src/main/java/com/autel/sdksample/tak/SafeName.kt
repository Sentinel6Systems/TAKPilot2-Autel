package com.autel.sdksample.tak

/**
 * THE AIRCRAFT CALLSIGN AND THE VIDEO BROADCAST ID MAY HOLD ONLY LETTERS, DIGITS, '-' AND '_'
 * (design decision). A broadcast id with a space makes the stream fail:
 * the id goes into the push URL and the path the server and every TAK
 * viewer use. The callsign takes the same rule because the broadcast id takes the callsign by
 * default ([followCallsign]). Pure, no Android import: SafeNameTest pins it. [SafeNameField]
 * puts it on an input field.
 */
object SafeName {

    fun isAllowed(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_'

    /** True when every character of [name] is allowed. An empty name is not this rule's job. */
    fun isValid(name: String): Boolean = name.all(::isAllowed)

    /** The characters of [name] that are not allowed, each once, for the field's error text. */
    fun badChars(name: String): String = name.filterNot(::isAllowed).toSet().joinToString("") {
        if (it == ' ') "space" else it.toString()
    }

    /** [text] with every character that is not allowed removed. For typing and pasting. */
    fun clean(text: CharSequence): String = text.filter(::isAllowed).toString()

    /**
     * THE BROADCAST ID FOLLOWS THE CALLSIGN unless someone set it to something else: the new id
     * when the callsign changes, or null to leave the id alone. An id that is empty, or still
     * the old callsign, follows; an id typed on purpose does not.
     */
    fun followCallsign(currentId: String, oldCallsign: String?, newCallsign: String): String? {
        if (newCallsign.isEmpty() || !isValid(newCallsign)) return null
        return if (currentId.isEmpty() || currentId == oldCallsign) newCallsign else null
    }
}
