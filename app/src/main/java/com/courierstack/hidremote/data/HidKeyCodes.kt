package com.courierstack.hidremote.data

/**
 * HID Keyboard/Keypad Usage Page (0x07) key codes.
 * 
 * These are the standard USB HID key codes used in keyboard reports.
 */
object HidKeyCodes {
    // Letters
    const val KEY_A = 0x04
    const val KEY_B = 0x05
    const val KEY_C = 0x06
    const val KEY_D = 0x07
    const val KEY_E = 0x08
    const val KEY_F = 0x09
    const val KEY_G = 0x0A
    const val KEY_H = 0x0B
    const val KEY_I = 0x0C
    const val KEY_J = 0x0D
    const val KEY_K = 0x0E
    const val KEY_L = 0x0F
    const val KEY_M = 0x10
    const val KEY_N = 0x11
    const val KEY_O = 0x12
    const val KEY_P = 0x13
    const val KEY_Q = 0x14
    const val KEY_R = 0x15
    const val KEY_S = 0x16
    const val KEY_T = 0x17
    const val KEY_U = 0x18
    const val KEY_V = 0x19
    const val KEY_W = 0x1A
    const val KEY_X = 0x1B
    const val KEY_Y = 0x1C
    const val KEY_Z = 0x1D

    // Numbers (top row)
    const val KEY_1 = 0x1E
    const val KEY_2 = 0x1F
    const val KEY_3 = 0x20
    const val KEY_4 = 0x21
    const val KEY_5 = 0x22
    const val KEY_6 = 0x23
    const val KEY_7 = 0x24
    const val KEY_8 = 0x25
    const val KEY_9 = 0x26
    const val KEY_0 = 0x27

    // Control keys
    const val KEY_ENTER = 0x28
    const val KEY_ESCAPE = 0x29
    const val KEY_BACKSPACE = 0x2A
    const val KEY_TAB = 0x2B
    const val KEY_SPACE = 0x2C
    const val KEY_MINUS = 0x2D
    const val KEY_EQUALS = 0x2E
    const val KEY_LEFT_BRACKET = 0x2F
    const val KEY_RIGHT_BRACKET = 0x30
    const val KEY_BACKSLASH = 0x31
    const val KEY_SEMICOLON = 0x33
    const val KEY_APOSTROPHE = 0x34
    const val KEY_GRAVE = 0x35
    const val KEY_COMMA = 0x36
    const val KEY_PERIOD = 0x37
    const val KEY_SLASH = 0x38
    const val KEY_CAPS_LOCK = 0x39

    // Function keys
    const val KEY_F1 = 0x3A
    const val KEY_F2 = 0x3B
    const val KEY_F3 = 0x3C
    const val KEY_F4 = 0x3D
    const val KEY_F5 = 0x3E
    const val KEY_F6 = 0x3F
    const val KEY_F7 = 0x40
    const val KEY_F8 = 0x41
    const val KEY_F9 = 0x42
    const val KEY_F10 = 0x43
    const val KEY_F11 = 0x44
    const val KEY_F12 = 0x45

    // Navigation keys
    const val KEY_PRINT_SCREEN = 0x46
    const val KEY_SCROLL_LOCK = 0x47
    const val KEY_PAUSE = 0x48
    const val KEY_INSERT = 0x49
    const val KEY_HOME = 0x4A
    const val KEY_PAGE_UP = 0x4B
    const val KEY_DELETE = 0x4C
    const val KEY_END = 0x4D
    const val KEY_PAGE_DOWN = 0x4E
    const val KEY_RIGHT_ARROW = 0x4F
    const val KEY_LEFT_ARROW = 0x50
    const val KEY_DOWN_ARROW = 0x51
    const val KEY_UP_ARROW = 0x52

    // Numpad
    const val KEY_NUM_LOCK = 0x53
    const val KEY_NUMPAD_DIVIDE = 0x54
    const val KEY_NUMPAD_MULTIPLY = 0x55
    const val KEY_NUMPAD_MINUS = 0x56
    const val KEY_NUMPAD_PLUS = 0x57
    const val KEY_NUMPAD_ENTER = 0x58
    const val KEY_NUMPAD_1 = 0x59
    const val KEY_NUMPAD_2 = 0x5A
    const val KEY_NUMPAD_3 = 0x5B
    const val KEY_NUMPAD_4 = 0x5C
    const val KEY_NUMPAD_5 = 0x5D
    const val KEY_NUMPAD_6 = 0x5E
    const val KEY_NUMPAD_7 = 0x5F
    const val KEY_NUMPAD_8 = 0x60
    const val KEY_NUMPAD_9 = 0x61
    const val KEY_NUMPAD_0 = 0x62
    const val KEY_NUMPAD_DOT = 0x63

    // Modifier keys (for reference - sent in modifier byte, not key array)
    const val MOD_LEFT_CTRL = 0x01
    const val MOD_LEFT_SHIFT = 0x02
    const val MOD_LEFT_ALT = 0x04
    const val MOD_LEFT_GUI = 0x08
    const val MOD_RIGHT_CTRL = 0x10
    const val MOD_RIGHT_SHIFT = 0x20
    const val MOD_RIGHT_ALT = 0x40
    const val MOD_RIGHT_GUI = 0x80

    // Application/Menu key
    const val KEY_APPLICATION = 0x65

    // Media keys (Consumer Control page 0x0C)
    const val MEDIA_PLAY_PAUSE = 0xCD
    const val MEDIA_STOP = 0xB7
    const val MEDIA_NEXT = 0xB5
    const val MEDIA_PREV = 0xB6
    const val MEDIA_VOLUME_UP = 0xE9
    const val MEDIA_VOLUME_DOWN = 0xEA
    const val MEDIA_MUTE = 0xE2

    /**
     * Convert a character to its HID key code and modifier.
     * @return Pair of (keyCode, modifiers) or null if not mappable
     */
    fun charToKeyCode(char: Char): Pair<Int, Int>? {
        return when (char) {
            // Lowercase letters
            in 'a'..'z' -> Pair(KEY_A + (char - 'a'), 0)
            // Uppercase letters
            in 'A'..'Z' -> Pair(KEY_A + (char - 'A'), MOD_LEFT_SHIFT)
            // Numbers
            in '0'..'9' -> {
                val code = if (char == '0') KEY_0 else KEY_1 + (char - '1')
                Pair(code, 0)
            }
            // Shifted numbers
            '!' -> Pair(KEY_1, MOD_LEFT_SHIFT)
            '@' -> Pair(KEY_2, MOD_LEFT_SHIFT)
            '#' -> Pair(KEY_3, MOD_LEFT_SHIFT)
            '$' -> Pair(KEY_4, MOD_LEFT_SHIFT)
            '%' -> Pair(KEY_5, MOD_LEFT_SHIFT)
            '^' -> Pair(KEY_6, MOD_LEFT_SHIFT)
            '&' -> Pair(KEY_7, MOD_LEFT_SHIFT)
            '*' -> Pair(KEY_8, MOD_LEFT_SHIFT)
            '(' -> Pair(KEY_9, MOD_LEFT_SHIFT)
            ')' -> Pair(KEY_0, MOD_LEFT_SHIFT)
            // Punctuation
            ' ' -> Pair(KEY_SPACE, 0)
            '\n' -> Pair(KEY_ENTER, 0)
            '\t' -> Pair(KEY_TAB, 0)
            '-' -> Pair(KEY_MINUS, 0)
            '_' -> Pair(KEY_MINUS, MOD_LEFT_SHIFT)
            '=' -> Pair(KEY_EQUALS, 0)
            '+' -> Pair(KEY_EQUALS, MOD_LEFT_SHIFT)
            '[' -> Pair(KEY_LEFT_BRACKET, 0)
            '{' -> Pair(KEY_LEFT_BRACKET, MOD_LEFT_SHIFT)
            ']' -> Pair(KEY_RIGHT_BRACKET, 0)
            '}' -> Pair(KEY_RIGHT_BRACKET, MOD_LEFT_SHIFT)
            '\\' -> Pair(KEY_BACKSLASH, 0)
            '|' -> Pair(KEY_BACKSLASH, MOD_LEFT_SHIFT)
            ';' -> Pair(KEY_SEMICOLON, 0)
            ':' -> Pair(KEY_SEMICOLON, MOD_LEFT_SHIFT)
            '\'' -> Pair(KEY_APOSTROPHE, 0)
            '"' -> Pair(KEY_APOSTROPHE, MOD_LEFT_SHIFT)
            '`' -> Pair(KEY_GRAVE, 0)
            '~' -> Pair(KEY_GRAVE, MOD_LEFT_SHIFT)
            ',' -> Pair(KEY_COMMA, 0)
            '<' -> Pair(KEY_COMMA, MOD_LEFT_SHIFT)
            '.' -> Pair(KEY_PERIOD, 0)
            '>' -> Pair(KEY_PERIOD, MOD_LEFT_SHIFT)
            '/' -> Pair(KEY_SLASH, 0)
            '?' -> Pair(KEY_SLASH, MOD_LEFT_SHIFT)
            else -> null
        }
    }
}

/**
 * Mouse button constants.
 */
object MouseButtons {
    const val LEFT = 0x01
    const val RIGHT = 0x02
    const val MIDDLE = 0x04
    const val BUTTON4 = 0x08
    const val BUTTON5 = 0x10
}
