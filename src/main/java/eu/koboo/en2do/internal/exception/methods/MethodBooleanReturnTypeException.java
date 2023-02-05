package eu.koboo.en2do.internal.exception.methods;

import java.lang.reflect.Method;

public class MethodBooleanReturnTypeException extends Exception {

    public MethodBooleanReturnTypeException(Method method, Class<?> repoClass) {
        super("Methods, which return " + Boolean.class.getName() + " has to start with keywords \"deleteBy\", " +
                "\"existsBy\" or \"updateFieldsBy\"! Please correct the method " + method.getName() + " in " + repoClass.getName() + ".");
    }
}
