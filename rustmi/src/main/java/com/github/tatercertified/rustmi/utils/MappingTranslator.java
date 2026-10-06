package com.github.tatercertified.rustmi.utils;

import com.github.tatercertified.rustmi.Config;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;

public final class MappingTranslator {
    private static final String NAMESPACE = "intermediary";
    private static final MappingResolver resolver = FabricLoader.getInstance().getMappingResolver();
    public static final boolean IS_DEV = !resolver.getCurrentRuntimeNamespace().equals(NAMESPACE);


    public static String remapFieldName(String intermediaryOwner, String intermediaryField, String fieldDesc) {
        if (IS_DEV) {
            String resolved = resolver.mapFieldName(
                    NAMESPACE,
                    intermediaryOwner,
                    intermediaryField,
                    fieldDesc
            );

            if (Config.getInstance().debug()) {
                FieldStripper.STRIPPER_LOGGER.info("Remapping {} to {}", intermediaryField, resolved);
            }
            return resolved;
        } else {
            return intermediaryField;
        }
    }

    public static String remapMethodName(String intermediaryOwner, String intermediaryMethod, Class<?> returnType, Class<?>[] parameters) {
        if (IS_DEV) {
            final String[] unmap = new String[parameters.length];
            for (int i = 0; i < unmap.length; i++) {
                unmap[i] = unmap(parameters[i]);
            }

            String resolved = resolver.mapMethodName(
                    NAMESPACE,
                    intermediaryOwner,
                    intermediaryMethod,
                    '(' + String.join("", unmap) + ')' + unmap(returnType)
            );

            if (Config.getInstance().debug()) {
                FieldStripper.STRIPPER_LOGGER.info("Remapping {} to {}", intermediaryMethod, resolved);
            }
            return resolved;
        } else {
            return intermediaryMethod;
        }
    }

    /**
     * Remaps classes
     * @author Ampflower
     * @param intermediaryClass dot path to class
     * @return Remapped class to current runtime mapping
     */
    public static String remapClassName(String intermediaryClass) {
        if (IS_DEV) {
            String resolved = resolver.mapClassName(NAMESPACE, intermediaryClass);
            if (Config.getInstance().debug()) {
                FieldStripper.STRIPPER_LOGGER.info("Remapping {} to {}", intermediaryClass, resolved);
            }
            return resolved;
        } else {
            return intermediaryClass;
        }
    }

    /**
     * Unmaps classes to intermediary
     * @author Ampflower
     * @param clazz Class to unmap
     * @return Unmapped class
     */
    private static String unmap(Class<?> clazz) {
        if (clazz.isPrimitive()) {
            return clazz.descriptorString();
        }
        return 'L' + toIntermediary(clazz).replace('.', '/') + ';';
    }

    /**
     * Unmaps classes
     * @author Ampflower
     * @param clazz Class to unmap
     * @return Unmapped class name
     */
    public static String toIntermediary(Class<?> clazz) {
        return resolver.unmapClassName(NAMESPACE, clazz.getName());
    }
}
