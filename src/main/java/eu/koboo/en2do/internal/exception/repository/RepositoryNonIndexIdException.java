package eu.koboo.en2do.internal.exception.repository;

import eu.koboo.en2do.repository.SeparateEntityId;
import eu.koboo.en2do.repository.entity.NonIndex;

public class RepositoryNonIndexIdException extends Exception {

    public RepositoryNonIndexIdException(Class<?> repoClass) {
        super("The repository " + repoClass.getName() + " uses " + NonIndex.class + ", but is missing " +
            SeparateEntityId.class + ". This is not allowed, because the ObjectId of mongodb has to be unique!");
    }
}
