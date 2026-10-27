package com.github.tatercertified.nalim.rust;

import one.nalim.Link;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Java declarations of the native surface against the C header cbindgen generates from
 * the Rust crate. Both backends pass arguments exactly as declared in Java, so a drifted
 * parameter list or width corrupts arguments silently (Nalim) or per platform ABI (FFM).
 * Also checks that the generated facade and FFM bindings expose the same signatures.
 *
 * <p>Classes are inspected without initialization: initializing them would link the library.
 */
public final class NativeBindingsContractTest {
    private NativeBindingsContractTest() {}

    private static final Pattern PROTOTYPE =
            Pattern.compile("([A-Za-z_][\\w ]*?\\**)\\s*\\b(oxi_\\w+)\\(([^;{)]*)\\);");

    public static void main(String[] args) throws Exception {
        Map<String, CFunction> header = parseHeader(Path.of(args[0]));
        ClassLoader loader = NativeBindingsContractTest.class.getClassLoader();
        Map<String, Method> nalim = staticMethods(Class.forName(pkg("NalimBindings"), false, loader), true);
        Map<String, Method> facade = staticMethods(Class.forName(pkg("RustmiLib"), false, loader), false);
        Map<String, Method> ffm = staticMethods(Class.forName(pkg("FfmBindings"), false, loader), false);

        List<String> errors = new ArrayList<>();
        if (!nalim.keySet().equals(facade.keySet()) || !nalim.keySet().equals(ffm.keySet())) {
            errors.add("function sets differ: nalim=" + nalim.keySet() + " facade=" + facade.keySet()
                    + " ffm=" + ffm.keySet());
        }
        for (Method declared : nalim.values()) {
            String name = declared.getName();
            for (Method other : new Method[]{facade.get(name), ffm.get(name)}) {
                if (other != null && (other.getReturnType() != declared.getReturnType()
                        || !Arrays.equals(other.getParameterTypes(), declared.getParameterTypes()))) {
                    errors.add(name + ": " + other.getDeclaringClass().getSimpleName()
                            + " signature differs from NalimBindings");
                }
            }
            Link link = declared.getAnnotation(Link.class);
            String symbol = link != null && !link.name().isEmpty() ? link.name() : name;
            CFunction c = header.get(symbol);
            if (c == null) {
                errors.add(name + ": symbol " + symbol + " is not exported by the native library");
                continue;
            }
            check(errors, name, "return", declared.getReturnType(), c.ret);
            Class<?>[] params = declared.getParameterTypes();
            if (params.length != c.params.size()) {
                errors.add(name + ": " + params.length + " Java parameters, C has " + c.params.size());
                continue;
            }
            for (int i = 0; i < params.length; i++) {
                check(errors, name, "parameter " + i, params[i], c.params.get(i));
            }
        }
        if (!errors.isEmpty()) {
            throw new AssertionError(errors.size() + " native binding mismatch(es):\n  "
                    + String.join("\n  ", errors));
        }
        System.out.println("NativeBindingsContractTest: " + nalim.size() + " bindings match " + args[0]);
    }

    private static String pkg(String simpleName) {
        return "com.github.tatercertified.nalim.rust." + simpleName;
    }

    private static void check(List<String> errors, String fn, String what, Class<?> java, String cType) {
        Class<?> expected = javaType(cType);
        if (expected != java) {
            errors.add(fn + ": " + what + " is " + java + " in Java but '" + cType + "' in C"
                    + (expected == null ? " (unsupported)" : " (expected " + expected + ")"));
        }
    }

    /** Integer widths must match exactly; signedness is free (Java has no unsigned types). */
    private static Class<?> javaType(String cType) {
        String t = cType.replace("const ", "").trim();
        if (t.endsWith("*")) {
            return long.class;   // addresses travel as long
        }
        return switch (t) {
            case "void" -> void.class;
            case "int8_t", "uint8_t" -> byte.class;   // never bool: a Rust bool outside 0/1 is UB
            case "int16_t", "uint16_t" -> short.class;
            case "int32_t", "uint32_t" -> int.class;
            case "int64_t", "uint64_t", "intptr_t", "uintptr_t" -> long.class;
            case "float" -> float.class;
            case "double" -> double.class;
            default -> null;
        };
    }

    private static Map<String, Method> staticMethods(Class<?> type, boolean nativeOnly) {
        Map<String, Method> methods = new TreeMap<>();
        for (Method m : type.getDeclaredMethods()) {
            int mod = m.getModifiers();
            if (!Modifier.isStatic(mod) || !Modifier.isPublic(mod) || nativeOnly && !Modifier.isNative(mod)) {
                continue;
            }
            if (methods.put(m.getName(), m) != null) {
                throw new AssertionError(type.getSimpleName() + " overloads " + m.getName());
            }
        }
        return methods;
    }

    private record CFunction(String ret, List<String> params) {}

    private static Map<String, CFunction> parseHeader(Path path) throws Exception {
        String text = Files.readString(path)
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("//[^\\n]*", "")
                .replaceAll("\\s+", " ");
        Map<String, CFunction> functions = new HashMap<>();
        Matcher m = PROTOTYPE.matcher(text);
        while (m.find()) {
            List<String> params = new ArrayList<>();
            String raw = m.group(3).trim();
            if (!raw.isEmpty() && !raw.equals("void")) {
                for (String param : raw.split(",")) {
                    String p = param.trim();
                    // Drop the parameter name; keep '*' attached to the type.
                    int cut = Math.max(p.lastIndexOf(' '), p.lastIndexOf('*'));
                    params.add(p.substring(0, cut + 1).trim());
                }
            }
            functions.put(m.group(2), new CFunction(m.group(1).trim(), params));
        }
        if (functions.isEmpty()) {
            throw new AssertionError("No native prototypes found in " + path);
        }
        return functions;
    }
}
