/*
 * This file is part of ClassGraph.
 *
 * Author: Luke Hutchison
 *
 * Hosted at: https://github.com/classgraph/classgraph
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2019 Luke Hutchison
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package nonapi.io.github.classgraph.json;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.concurrent.Callable;

import nonapi.io.github.classgraph.reflection.ReflectionUtils;

/** Utils for Java serialization and deserialization. */
public final class JSONUtils {
    private static Method isAccessibleMethod;
    private static Method setAccessibleMethod;
    private static Method trySetAccessibleMethod;

    static {
        // Find deprecated methods isAccessible/setAccessible, to remove compile-time warnings
        // TODO Switch to using  MethodHandles once this is fixed:
        // https://github.com/mojohaus/animal-sniffer/issues/67
        try {
            isAccessibleMethod = AccessibleObject.class.getDeclaredMethod("isAccessible");
        } catch (final Throwable t) {
            // Ignore
        }
        try {
            setAccessibleMethod = AccessibleObject.class.getDeclaredMethod("setAccessible", boolean.class);
        } catch (final Throwable t) {
            // Ignore
        }
        try {
            trySetAccessibleMethod = AccessibleObject.class.getDeclaredMethod("trySetAccessible");
        } catch (final Throwable t) {
            // Ignore
        }
    }

    private static boolean isAccessible(final AccessibleObject obj) {
        if (isAccessibleMethod != null) {
            // JDK 7/8: use isAccessible (deprecated in JDK 9+)
            try {
                if ((Boolean) isAccessibleMethod.invoke(obj)) {
                    return true;
                }
            } catch (final Throwable e) {
                // Ignore
            }
        }
        return false;
    }

    private static boolean tryMakeAccessible(final AccessibleObject obj) {
        if (setAccessibleMethod != null) {
            try {
                setAccessibleMethod.invoke(obj, true);
                return true;
            } catch (final Throwable e) {
                // Ignore
            }
        }
        if (trySetAccessibleMethod != null) {
            try {
                if ((Boolean) trySetAccessibleMethod.invoke(obj)) {
                    return true;
                }
            } catch (final Throwable e) {
                // Ignore
            }
        }
        return false;
    }

    public static boolean makeAccessible(final AccessibleObject obj) {
        // This reflection code is duplicated from StandardReflectionDriver, because calling
        // ReflectionUtils.reflectionDriver.makeAccessible(obj) does not work when called from here
        // (private fields can't be accessed from outside this package even after calling setAccessible(true))
        if (isAccessible(obj) || tryMakeAccessible(obj)) {
            return true;
        }
        try {
            return ReflectionUtils.doPrivileged(new Callable<Boolean>() {
                @Override
                public Boolean call() throws Exception {
                    return tryMakeAccessible(obj);
                }
            });
        } catch (final Throwable t) {
            return false;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * JSON object key name for objects that are linked to from more than one object. Key name is only used if the
     * class that a JSON object was serialized from does not have its own id field annotated with {@link Id}.
     */
    static final String ID_KEY = "__ID";

    /** JSON object reference id prefix. */
    static final String ID_PREFIX = "[#";

    /** JSON object reference id suffix. */
    static final String ID_SUFFIX = "]";

    /** JSON character-to-string escaping replacements -- see http://www.json.org/ under "string". */
    private static final String[] JSON_CHAR_REPLACEMENTS = new String[256];

    static {
        for (int c = 0; c < 256; c++) {
            if (c == 32) {
                c = 127;
            }
            final int nibble1 = c >> 4;
            final char hexDigit1 = nibble1 <= 9 ? (char) ('0' + nibble1) : (char) ('A' + nibble1 - 10);
            final int nibble0 = c & 0xf;
            final char hexDigit0 = nibble0 <= 9 ? (char) ('0' + nibble0) : (char) ('A' + nibble0 - 10);
            JSON_CHAR_REPLACEMENTS[c] = "\\u00" + hexDigit1 + "" + hexDigit0;
        }
        JSON_CHAR_REPLACEMENTS['"'] = "\\\"";
        JSON_CHAR_REPLACEMENTS['\\'] = "\\\\";
        JSON_CHAR_REPLACEMENTS['\n'] = "\\n";
        JSON_CHAR_REPLACEMENTS['\r'] = "\\r";
        JSON_CHAR_REPLACEMENTS['\t'] = "\\t";
        JSON_CHAR_REPLACEMENTS['\b'] = "\\b";
        JSON_CHAR_REPLACEMENTS['\f'] = "\\f";
    }

    /** Lookup table for fast indenting. */
    private static final String[] INDENT_LEVELS = new String[17];
    static {
        final StringBuilder buf = new StringBuilder();
        for (int i = 0; i < INDENT_LEVELS.length; i++) {
            INDENT_LEVELS[i] = buf.toString();
            buf.append(' ');
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Constructor.
     */
    private JSONUtils() {
        // Cannot be constructed
    }

    /**
     * Escape a string to be surrounded in double quotes in JSON.
     *
     * @param unsafeStr
     *            the unsafe str
     * @param buf
     *            the buf
     */
    static void escapeJSONString(final String unsafeStr, final StringBuilder buf) {
        if (unsafeStr == null) {
            return;
        }
        // Fast path
        boolean needsEscaping = false;
        for (int i = 0, n = unsafeStr.length(); i < n; i++) {
            final char c = unsafeStr.charAt(i);
            if (c > 0xff || JSON_CHAR_REPLACEMENTS[c] != null) {
                needsEscaping = true;
                break;
            }
        }
        if (!needsEscaping) {
            buf.append(unsafeStr);
            return;
        }
        // Slow path
        for (int i = 0, n = unsafeStr.length(); i < n; i++) {
            final char c = unsafeStr.charAt(i);
            if (c > 0xff) {
                buf.append("\\u");
                final int nibble3 = ((c) & 0xf000) >> 12;
                buf.append(nibble3 <= 9 ? (char) ('0' + nibble3) : (char) ('A' + nibble3 - 10));
                final int nibble2 = ((c) & 0xf00) >> 8;
                buf.append(nibble2 <= 9 ? (char) ('0' + nibble2) : (char) ('A' + nibble2 - 10));
                final int nibble1 = ((c) & 0xf0) >> 4;
                buf.append(nibble1 <= 9 ? (char) ('0' + nibble1) : (char) ('A' + nibble1 - 10));
                final int nibble0 = ((c) & 0xf);
                buf.append(nibble0 <= 9 ? (char) ('0' + nibble0) : (char) ('A' + nibble0 - 10));
            } else {
                final String replacement = JSON_CHAR_REPLACEMENTS[c];
                if (replacement == null) {
                    buf.append(c);
                } else {
                    buf.append(replacement);
                }
            }
        }
    }

    /**
     * Escape a string to be surrounded in double quotes in JSON.
     * 
     * @param unsafeStr
     *            The string to escape.
     * @return The escaped string.
     */
    public static String escapeJSONString(final String unsafeStr) {
        final StringBuilder buf = new StringBuilder(unsafeStr.length() * 2);
        escapeJSONString(unsafeStr, buf);
        return buf.toString();
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Indent (depth * indentWidth) spaces.
     *
     * @param depth
     *            the depth
     * @param indentWidth
     *            the indent width
     * @param buf
     *            the buf
     */
    static void indent(final int depth, final int indentWidth, final StringBuilder buf) {
        final int maxIndent = INDENT_LEVELS.length - 1;
        for (int d = depth * indentWidth; d > 0;) {
            final int n = Math.min(d, maxIndent);
            buf.append(INDENT_LEVELS[n]);
            d -= n;
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get a field value, appropriately handling primitive-typed fields.
     *
     * @param containingObj
     *            the containing object
     * @param field
     *            the field
     * @return the field value
     * @throws IllegalArgumentException
     *             if the specified object is not an instance of the class or interface declaring the underlying
     *             field
     * @throws IllegalAccessException
     *             if the field cannot be read
     */
    static Object getFieldValue(final Object containingObj, final Field field)
            throws IllegalArgumentException, IllegalAccessException {
        // return ReflectionUtils.getFieldVal(true, containingObj, field.getName());
        final Class<?> fieldType = field.getType();
        if (fieldType == Integer.TYPE) {
            return field.getInt(containingObj);
        } else if (fieldType == Long.TYPE) {
            return field.getLong(containingObj);
        } else if (fieldType == Short.TYPE) {
            return field.getShort(containingObj);
        } else if (fieldType == Double.TYPE) {
            return field.getDouble(containingObj);
        } else if (fieldType == Float.TYPE) {
            return field.getFloat(containingObj);
        } else if (fieldType == Boolean.TYPE) {
            return field.getBoolean(containingObj);
        } else if (fieldType == Byte.TYPE) {
            return field.getByte(containingObj);
        } else if (fieldType == Character.TYPE) {
            return field.getChar(containingObj);
        } else {
            return field.get(containingObj);
        }
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Return true for classes that can be equal to a basic value type (types that can be converted directly to and
     * from string representation).
     *
     * @param cls
     *            the class
     * @return true, if the class is a basic value type
     */
    static boolean isBasicValueType(final Class<?> cls) {
        return cls == String.class //
                || cls == Integer.class || cls == Integer.TYPE //
                || cls == Long.class || cls == Long.TYPE //
                || cls == Short.class || cls == Short.TYPE //
                || cls == Float.class || cls == Float.TYPE //
                || cls == Double.class || cls == Double.TYPE //
                || cls == Byte.class || cls == Byte.TYPE //
                || cls == Character.class || cls == Character.TYPE //
                || cls == Boolean.class || cls == Boolean.TYPE //
                || cls.isEnum() //
                || cls == Class.class;
    }

    /**
     * Return true for types that can be converted directly to and from string representation.
     *
     * @param type
     *            the type
     * @return true, if the type is a basic value type
     */
    static boolean isBasicValueType(final Type type) {
        if (type instanceof Class<?>) {
            return isBasicValueType((Class<?>) type);
        } else if (type instanceof ParameterizedType) {
            return isBasicValueType(((ParameterizedType) type).getRawType());
        } else {
            return false;
        }
    }

    /**
     * Return true for objects that can be converted directly to and from string representation.
     *
     * @param obj
     *            the object
     * @return true, if the object is null or of basic value type
     */
    static boolean isBasicValueType(final Object obj) {
        return obj == null || obj instanceof String || obj instanceof Integer || obj instanceof Boolean
                || obj instanceof Long || obj instanceof Float || obj instanceof Double || obj instanceof Short
                || obj instanceof Byte || obj instanceof Character || obj.getClass().isEnum()
                || obj instanceof Class;
    }

    /**
     * Return true for objects that are collections or arrays (i.e. objects that are convertible to a JSON array).
     *
     * @param obj
     *            the object
     * @return true, if the object is a collection or array
     */
    static boolean isCollectionOrArray(final Object obj) {
        final Class<?> cls = obj.getClass();
        return Collection.class.isAssignableFrom(cls) || cls.isArray();
    }

    // -------------------------------------------------------------------------------------------------------------

    /**
     * Get the raw type from a Type.
     *
     * @param type
     *            the type
     * @return the raw type
     * @throws IllegalArgumentException
     *             if passed a TypeVariable or anything other than a {@code Class<?>} reference or
     *             {@link ParameterizedType}.
     */
    static Class<?> getRawType(final Type type) {
        if (type instanceof Class<?>) {
            return (Class<?>) type;
        } else if (type instanceof ParameterizedType) {
            return (Class<?>) ((ParameterizedType) type).getRawType();
        } else {
            throw new IllegalArgumentException("Illegal type: " + type);
        }
    }

    /**
     * Check if a field is serializable. Don't serialize transient, final, synthetic, or inaccessible fields.
     * 
     * <p>
     * N.B. Tries to set field to accessible, which will require an "opens" declarations from modules that want to
     * allow this introspection.
     *
     * @param field
     *            the field
     * @param onlySerializePublicFields
     *            if true, only serialize public fields
     * @return true if the field is serializable
     */
    static boolean fieldIsSerializable(final Field field, final boolean onlySerializePublicFields) {
        final int modifiers = field.getModifiers();
        if ((!onlySerializePublicFields || Modifier.isPublic(modifiers)) && !Modifier.isTransient(modifiers)
                && !Modifier.isFinal(modifiers) && ((modifiers & 0x1000 /* synthetic */) == 0)) {
            return makeAccessible(field);
        }
        return false;
    }
}
