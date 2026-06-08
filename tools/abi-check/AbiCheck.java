import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;

/** Checks that bytecode member refs (Class.member:descriptor) resolve against a target jar set, JVM-style (hierarchy walk). */
public class AbiCheck {
    static URLClassLoader loader;

    public static void main(String[] args) throws Exception {
        List<URL> urls = new ArrayList<>();
        for (int i = 1; i < args.length; i++) urls.add(new File(args[i]).toURI().toURL());
        loader = new URLClassLoader(urls.toArray(new URL[0]), null);

        int missing = 0, unknown = 0, total = 0;
        for (String line : Files.readAllLines(Path.of(args[0]))) {
            if (line.isBlank()) continue;
            total++;
            int dot = line.indexOf('.');
            String cls = line.substring(0, dot).replace('/', '.');
            String rest = line.substring(dot + 1);
            int colon = rest.indexOf(':');
            String name = rest.substring(0, colon).replace("\"", "");
            String desc = rest.substring(colon + 1);
            try {
                Class<?> c = Class.forName(cls, false, loader);
                if (!resolves(c, name, desc)) { System.out.println("MISSING " + line); missing++; }
            } catch (Throwable t) {
                System.out.println("UNKNOWN " + line + "  (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")");
                unknown++;
            }
        }
        System.out.println("== total " + total + ", missing " + missing + ", unresolvable " + unknown);
    }

    static boolean resolves(Class<?> c, String name, String desc) {
        if (name.equals("<init>")) {
            for (Constructor<?> k : c.getDeclaredConstructors())
                if (desc(k.getParameterTypes(), void.class).equals(desc)) return true;
            return false;
        }
        boolean isField = !desc.startsWith("(");
        for (Class<?> cur = c; cur != null; cur = cur.getSuperclass()) {
            if (check(cur, name, desc, isField)) return true;
            if (checkInterfaces(cur, name, desc, isField)) return true;
        }
        return false;
    }

    static boolean checkInterfaces(Class<?> c, String name, String desc, boolean isField) {
        for (Class<?> itf : c.getInterfaces()) {
            if (check(itf, name, desc, isField) || checkInterfaces(itf, name, desc, isField)) return true;
        }
        return false;
    }

    static boolean check(Class<?> c, String name, String desc, boolean isField) {
        try {
            if (isField) {
                for (Field f : c.getDeclaredFields())
                    if (f.getName().equals(name) && type(f.getType()).equals(desc)) return true;
            } else {
                for (Method m : c.getDeclaredMethods())
                    if (m.getName().equals(name) && desc(m.getParameterTypes(), m.getReturnType()).equals(desc)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    static String desc(Class<?>[] params, Class<?> ret) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : params) sb.append(type(p));
        return sb.append(')').append(type(ret)).toString();
    }

    static String type(Class<?> c) {
        if (c == void.class) return "V"; if (c == int.class) return "I"; if (c == boolean.class) return "Z";
        if (c == byte.class) return "B"; if (c == char.class) return "C"; if (c == short.class) return "S";
        if (c == long.class) return "J"; if (c == float.class) return "F"; if (c == double.class) return "D";
        if (c.isArray()) return "[" + type(c.getComponentType());
        return "L" + c.getName().replace('.', '/') + ";";
    }
}
