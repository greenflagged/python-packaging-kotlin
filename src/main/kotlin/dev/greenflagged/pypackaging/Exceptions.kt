package dev.greenflagged.pypackaging

/**
 * Base class for this library's exceptions. Like Python's `ValueError` subclasses in
 * `packaging`, they signal invalid input.
 */
public open class PackagingException(message: String) : IllegalArgumentException(message)

/** A string is not a valid PEP 440 version. Python: `packaging.version.InvalidVersion`. */
public class InvalidVersionException(message: String) : PackagingException(message)

/** A string is not a valid PEP 440 specifier. Python: `packaging.specifiers.InvalidSpecifier`. */
public class InvalidSpecifierException(message: String) : PackagingException(message)

/** A string is not a valid PEP 508 marker. Python: `packaging.markers.InvalidMarker`. */
public class InvalidMarkerException(message: String) : PackagingException(message)

/**
 * A marker compares values in a way that has no defined result, such as `~=` on a
 * non-version value. Python: `packaging.markers.UndefinedComparison`.
 */
public class UndefinedComparisonException(message: String) : PackagingException(message)

/**
 * A marker refers to a variable the evaluation environment does not define.
 * Python: `packaging.markers.UndefinedEnvironmentName`.
 */
public class UndefinedEnvironmentNameException(
    /** The variable name, e.g. `python_version`. */
    public val name: String,
) : PackagingException("'$name' is not defined in the marker environment")

/** A string is not a valid PEP 508 requirement. Python: `packaging.requirements.InvalidRequirement`. */
public class InvalidRequirementException(message: String) : PackagingException(message)

/** A project name is not valid. Python: `packaging.utils.InvalidName`. */
public class InvalidNameException(message: String) : PackagingException(message)

/** A wheel filename is not valid. Python: `packaging.utils.InvalidWheelFilename`. */
public class InvalidWheelFilenameException(message: String) : PackagingException(message)

/** An sdist filename is not valid. Python: `packaging.utils.InvalidSdistFilename`. */
public class InvalidSdistFilenameException(message: String) : PackagingException(message)

/** A wheel tag is not valid. Python: `packaging.tags.InvalidTag`. */
public open class InvalidTagException(message: String) : PackagingException(message)

/**
 * A compressed tag set's components are not in sorted order.
 * Python: `packaging.tags.UnsortedTagsError`.
 */
public class UnsortedTagsException(message: String) : InvalidTagException(message)

/**
 * A compressed tag set expands to more tags than allowed.
 * Python: `packaging.tags.TooManyTagsError`.
 */
public class TooManyTagsException(message: String) : InvalidTagException(message)
