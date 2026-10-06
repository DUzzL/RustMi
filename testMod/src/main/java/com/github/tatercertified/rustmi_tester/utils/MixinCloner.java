package com.github.tatercertified.rustmi_tester.utils;

import com.github.tatercertified.rustmi.Config;
import com.github.tatercertified.rustmi.RustmiMixinPlugin;
import com.github.tatercertified.rustmi_tester.RustmiTester;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import java.io.IOException;
import java.io.InputStream;

import static org.objectweb.asm.Opcodes.*;

public final class MixinCloner {
    public static Class<?> cloneStaticMethods(String originalInternalName, String newInternalName) throws IOException {
        ClassReader reader = new ClassReader(streamClass(originalInternalName));
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

        ClassVisitor visitor = new ClassVisitor(ASM9, writer) {
            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                super.visit(V17, ACC_PUBLIC, newInternalName, null, "java/lang/Object", null);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if ((access & ACC_STATIC) != 0 && !name.equals("<clinit>") && !name.equals("<init>")) {
                    return super.visitMethod(access, name, descriptor, signature, exceptions);
                }
                return null;
            }
        };

        reader.accept(visitor, ClassReader.SKIP_DEBUG);

        byte[] newClassBytes = writer.toByteArray();

        return new ClassLoader(MixinCloner.class.getClassLoader()) {
            public Class<?> define() {
                if (Config.getInstance().debug()) {
                    RustmiTester.TEST_LOGGER.info("Cloned {} to {}", originalInternalName, newInternalName);
                }
                return defineClass(newInternalName.replace('/', '.'), newClassBytes, 0, newClassBytes.length);
            }
        }.define();
    }

    /**
     * Gets the stream of the Mixin class
     * @param mixinPath Mixin class's dot path
     * @return InputStream to the Mixin Class file
     */
    private static InputStream streamClass(final String mixinPath) {
        return RustmiMixinPlugin.class.getResourceAsStream("/" + mixinPath.replace('.', '/') + ".class");
    }
}
