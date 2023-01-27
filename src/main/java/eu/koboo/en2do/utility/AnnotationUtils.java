package eu.koboo.en2do.utility;

import lombok.experimental.UtilityClass;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * A utility class for everything related to annotations
 */
@UtilityClass
public class AnnotationUtils {

    /**
     * This method is ued to get all annotations from an entity class, using while loop,
     * to iterate through all super-types.
     * @param entityClass The class, which should be scanned.
     * @param annotationClass the searched annotation
     * @return The Set with all found annotations of type A
     * @param <E> The generic type of the Class
     * @param <A> The generic type of the annotation Class
     */
    public <E, A extends Annotation> Set<A> collectAnnotations(Class<E> entityClass, Class<A> annotationClass) {
        Set<A> annotationSet = new HashSet<>();
        Class<?> clazz = entityClass;
        while (clazz != Object.class) {
            A[] indexArray = clazz.getAnnotationsByType(annotationClass);
            clazz = clazz.getSuperclass();
            if (indexArray.length == 0) {
                continue;
            }
            annotationSet.addAll(Arrays.asList(indexArray));
        }
        return annotationSet;
    }
}
