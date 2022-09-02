package eu.koboo.en2do.exception;

public class RepositoryNoTypeException extends Exception {

    public RepositoryNoTypeException(Class<?> repoClass) {
        super("Couldn't find any type parameters in repository " + repoClass.getName() + ".");
    }
}