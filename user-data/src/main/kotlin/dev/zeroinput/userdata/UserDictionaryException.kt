package dev.zeroinput.userdata

enum class UserDictionaryFailure {
    INVALID_FORMAT,
    CAPACITY_EXCEEDED,
    IMPORT_TOO_LARGE,
    WRITE_FAILED,
}

/** Contains no imported values, parser messages or underlying exception causes. */
class UserDictionaryException(val failure: UserDictionaryFailure) : IllegalArgumentException(failure.name)
