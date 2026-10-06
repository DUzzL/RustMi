package com.github.tatercertified.rustmi.utils.asm;

import com.github.tatercertified.rustmi.Config;
import com.github.tatercertified.rustmi.utils.Cleanup;
import com.github.tatercertified.rustmi.utils.FieldStripper;
import com.github.tatercertified.rustmi.utils.MappingTranslator;
import org.objectweb.asm.tree.ClassNode;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public final class StripProcessor {
    private static final List<Field> PROCESSED = new ArrayList<>();

    public static void processStrips(String clazzName, ClassNode node) {
        if (Config.getInstance().reducedMemoryUsage()) {
            for (Field field : Cleanup.class.getDeclaredFields()) {
                if (field.isAnnotationPresent(Strip.class) && !PROCESSED.contains(field)) {
                    field.setAccessible(true);
                    Strip annotation = field.getAnnotation(Strip.class);
                    if (clazzName.equals(MappingTranslator.remapClassName(annotation.className()))) {
                        try {
                            Object fieldObj = field.get(null);
                            if (fieldObj instanceof String[][] fields) {
                                FieldStripper.stripFieldsWithClassNode(annotation.className(), node, fields);
                                PROCESSED.add(field);
                            }
                        } catch (IllegalAccessException e) {
                            throw new RuntimeException(e);
                        }
                    }
                }
            }
        }
    }
}
