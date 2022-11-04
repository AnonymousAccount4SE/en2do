package eu.koboo.en2do;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import eu.koboo.en2do.exception.*;
import eu.koboo.en2do.index.CompoundIndex;
import eu.koboo.en2do.index.Id;
import eu.koboo.en2do.index.Index;
import eu.koboo.en2do.index.NonIndex;
import eu.koboo.en2do.repository.DropEntitiesOnStart;
import eu.koboo.en2do.repository.DropIndexesOnStart;
import eu.koboo.en2do.sort.annotation.Limit;
import eu.koboo.en2do.sort.annotation.Skip;
import eu.koboo.en2do.sort.annotation.SortBy;
import eu.koboo.en2do.sort.annotation.SortByArray;
import eu.koboo.en2do.sort.object.Sort;
import eu.koboo.en2do.utility.GenericUtils;
import eu.koboo.en2do.methods.FilterOperator;
import eu.koboo.en2do.methods.FilterType;
import eu.koboo.en2do.methods.MethodOperator;
import lombok.AccessLevel;
import lombok.experimental.FieldDefaults;
import org.bson.conversions.Bson;

import java.lang.reflect.*;
import java.util.*;
import java.util.regex.Pattern;

@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class RepositoryFactory {

    MongoManager manager;
    Map<Class<?>, Repository<?, ?>> repoRegistry;

    public RepositoryFactory(MongoManager manager) {
        this.manager = manager;
        this.repoRegistry = new HashMap<>();
    }

    @SuppressWarnings("unchecked")
    protected <E, ID, R extends Repository<E, ID>> R create(Class<R> repoClass) throws Exception {
        // Check for already created repository to avoid multiply instances of the same repository
        if (repoRegistry.containsKey(repoClass)) {
            return (R) repoRegistry.get(repoClass);
        }

        // Parse Entity and UniqueId type classes (Yea, it's very hacky, but works)
        Type[] repoGenericTypeArray = repoClass.getGenericInterfaces();
        Type repoGenericTypeParams = null;
        for (Type type : repoGenericTypeArray) {
            if (type.getTypeName().split("<")[0].equalsIgnoreCase(Repository.class.getName())) {
                repoGenericTypeParams = type;
                break;
            }
        }
        if (repoGenericTypeParams == null) {
            throw new RepositoryNoTypeException(repoClass);
        }

        String entityClassName = repoGenericTypeParams.getTypeName().split("<")[1].split(",")[0];
        Class<E> entityClass;
        Class<ID> entityUniqueIdClass = null;
        Set<String> entityFieldNameSet = new HashSet<>();
        Field entityUniqueIdField = null;

        Set<Field> allFields;

        // Searching for the actual classes by their respective names
        try {
            entityClass = (Class<E>) Class.forName(entityClassName);

            allFields = getAllFields(entityClass);
            if (allFields.size() == 0) {
                throw new RepositoryNoFieldsException(repoClass);
            }
            for (Field field : allFields) {
                String lowerFieldName = field.getName().toLowerCase(Locale.ROOT);
                if (entityFieldNameSet.contains(lowerFieldName)) {
                    throw new RepositoryDuplicatedFieldException(field, repoClass);
                }
                entityFieldNameSet.add(lowerFieldName);
                if (Modifier.isFinal(field.getModifiers())) {
                    throw new RepositoryFinalFieldException(field, repoClass);
                }
                if (!field.isAnnotationPresent(Id.class)) {
                    continue;
                }
                entityUniqueIdClass = (Class<ID>) field.getType();
                entityUniqueIdField = field;
                entityUniqueIdField.setAccessible(true);
            }
            if (entityUniqueIdClass == null) {
                throw new RepositoryIdNotFoundException(entityClass);
            }
        } catch (ClassNotFoundException e) {
            throw new RepositoryEntityNotFoundException(repoClass, e);
        }

        // These methods are ignored by our methods processing.
        List<String> ignoredMethods = Arrays.asList(
                // Predefined methods by Repository interface
                "getCollectionName", "getUniqueId", "getEntityClass", "getEntityUniqueIdClass",
                "findById", "findAll", "delete", "deleteById", "deleteAll", "drop",
                "save", "saveAll", "exists", "existsById",
                // Predefined methods by Java objects
                "toString", "hashCode", "equals", "notify", "notifyAll", "wait", "finalize", "clone");
        for (Method method : repoClass.getMethods()) {
            if (ignoredMethods.contains(method.getName())) {
                continue;
            }
            // Check for the return-types of the methods, and their defined names to match our pattern.
            checkReturnTypes(method, entityClass, repoClass);
            checkMethodOperation(method, entityClass, repoClass, allFields);
            checkSortOptions(method, entityClass, repoClass, allFields);
        }

        // Parse annotated collection name and create pojo-related mongo collection
        Collection collectionAnnotation = repoClass.getAnnotation(Collection.class);
        if (collectionAnnotation == null) {
            throw new RepositoryNameNotFoundException(repoClass);
        }
        String entityCollectionName = collectionAnnotation.value();
        MongoCollection<E> entityCollection = manager.getDatabase().getCollection(entityCollectionName, entityClass);

        // Drop all entities on start if annotation is present.
        if(repoClass.isAnnotationPresent(DropEntitiesOnStart.class)) {
            entityCollection.drop();
        }

        // Drop all indexes on start if annotation is present.
        if(repoClass.isAnnotationPresent(DropIndexesOnStart.class)) {
            entityCollection.dropIndexes();
        }

        // Creating an index on the uniqueIdentifier field of the entity to speed up queries,
        // but only if wanted. Users can disable that with the annotation.
        if (!entityUniqueIdField.isAnnotationPresent(NonIndex.class)) {
            entityCollection.createIndex(Indexes.ascending(entityUniqueIdField.getName()), new IndexOptions().unique(true));
        }
        Set<CompoundIndex> compoundIndexList = getAllEntityIndecies(entityClass);
        for (CompoundIndex compoundIndex : compoundIndexList) {
            // Checking if the field in the annotation exists in the entity class.
            Index[] fieldIndexes = compoundIndex.value();
            for (Index fieldIndex : fieldIndexes) {
                if (allFields.stream().map(Field::getName).noneMatch(fieldName -> fieldIndex.value().equalsIgnoreCase(fieldName))) {
                    throw new RepositoryIndexFieldNotFoundException(repoClass, fieldIndex.value());
                }
            }
            // Validated all fields and creating the indexes on the collection.
            List<Bson> indexBsonList = new ArrayList<>();
            for (Index fieldIndex : fieldIndexes) {
                String fieldName = fieldIndex.value();
                Bson bsonIndex;
                if (fieldIndex.ascending()) {
                    bsonIndex = Indexes.ascending(fieldName);
                } else {
                    bsonIndex = Indexes.descending(fieldName);
                }
                indexBsonList.add(bsonIndex);
            }
            IndexOptions indexOptions = new IndexOptions()
                    .unique(compoundIndex.uniqueIndex());
            entityCollection.createIndex(Indexes.compoundIndex(indexBsonList), indexOptions);
        }

        // Create dynamic repository proxy object
        ClassLoader repoClassLoader = repoClass.getClassLoader();
        Class<?>[] interfaces = new Class[]{repoClass};
        Class<Repository<E, ID>> actualClass = (Class<Repository<E, ID>>) repoClass;
        Repository<E, ID> repository = (Repository<E, ID>) Proxy.newProxyInstance(repoClassLoader, interfaces,
                new RepositoryInvocationHandler<>(this, entityCollectionName, entityCollection,
                        actualClass, entityClass, allFields, entityUniqueIdClass,
                        entityUniqueIdField));
        repoRegistry.put(repoClass, repository);
        return (R) repository;
    }

    private <E> void checkReturnTypes(Method method, Class<E> entityClass, Class<?> repoClass) throws Exception {
        Class<?> returnTypeClass = method.getReturnType();
        String methodName = method.getName();
        MethodOperator methodOperator = MethodOperator.parseMethodStartsWith(methodName);
        if (methodOperator == null) {
            throw new MethodNoMethodOperatorException(method, repoClass);
        }
        switch (methodOperator) {
            case FIND -> {
                if (GenericUtils.isTypeOf(List.class, returnTypeClass)) {
                    Class<?> listType = GenericUtils.getGenericTypeOfReturnList(method);
                    if (!listType.isAssignableFrom(entityClass)) {
                        throw new MethodFindListTypeException(method, entityClass, listType);
                    }
                    return;
                }
                if (GenericUtils.isTypeOf(entityClass, returnTypeClass)) {
                    return;
                }
                throw new MethodFindReturnTypeException(method, entityClass, repoClass);
            }
            case DELETE, EXISTS -> {
                if (!GenericUtils.isTypeOf(Boolean.class, returnTypeClass)) {
                    throw new MethodBooleanReturnTypeException(method, repoClass);
                }
            }
            case COUNT -> {
                if (!GenericUtils.isTypeOf(Long.class, returnTypeClass)) {
                    throw new MethodLongReturnTypeException(method, repoClass);
                }
            }
            default -> throw new MethodUnsupportedReturnTypeException(returnTypeClass, methodName, entityClass);
        }
    }

    // ReturnType already checked.
    private <E> void checkMethodOperation(Method method, Class<E> entityClass, Class<?> repoClass,
                                          Set<Field> fieldSet) throws Exception {
        String methodName = method.getName();
        MethodOperator methodOperator = MethodOperator.parseMethodStartsWith(methodName);
        if (methodOperator == null) {
            throw new MethodNoMethodOperatorException(method, repoClass);
        }
        String fieldFilterName = methodOperator.removeOperatorFrom(methodName);
        if (methodName.contains("And") && methodName.contains("Or")) {
            throw new MethodDuplicatedChainException(method, entityClass);
        }
        int expectedParameters = countExpectedParameters(entityClass, repoClass, method, fieldFilterName, fieldSet);
        Debugger.print("Method: " + method.getName());
        Debugger.print("ExpParams: " + expectedParameters);
        int parameterCount = method.getParameterCount();
        Debugger.print("ActParams: " + parameterCount);
        if (expectedParameters != parameterCount) {
            if (parameterCount > 0) {
                Class<?> lastParam = method.getParameterTypes()[parameterCount - 1];
                Debugger.print("lastParam: " + lastParam.getName());
                Debugger.print("AddParam: " + (expectedParameters + 1));
                Debugger.print("Assignable: " + lastParam.isAssignableFrom(Sort.class));
                if (lastParam.isAssignableFrom(Sort.class) && (expectedParameters + 1) != parameterCount) {
                    throw new MethodParameterCountException(method, repoClass, (expectedParameters + 1), parameterCount);
                }
            } else {
                throw new MethodParameterCountException(method, repoClass, expectedParameters, parameterCount);
            }
        }
        String[] fieldFilterSplitByType = fieldFilterName.contains("And") ?
                fieldFilterName.split("And") : fieldFilterName.split("Or");
        int nextIndex = 0;
        for (int i = 0; i < fieldFilterSplitByType.length; i++) {
            String fieldFilterPart = fieldFilterSplitByType[i];
            FilterType filterType = createFilterType(entityClass, repoClass, method, fieldFilterPart, fieldSet);
            checkParameterType(method, filterType, nextIndex, repoClass);
            nextIndex = i + filterType.operator().getExpectedParameterCount();
        }
    }

    private <E> int countExpectedParameters(Class<E> entityClass, Class<?> repoClass, Method method,
                                            String fieldFilterPart, Set<Field> fieldSet) throws Exception {
        String methodName = method.getName();
        if (!methodName.contains("And") && !methodName.contains("Or")) {
            FilterType filterType = createFilterType(entityClass, repoClass, method, fieldFilterPart, fieldSet);
            return filterType.operator().getExpectedParameterCount();
        }
        String[] fieldFilterPartSplitByType = fieldFilterPart.contains("And") ?
                fieldFilterPart.split("And") : fieldFilterPart.split("Or");
        int expectedCount = 0;
        for (String fieldFilterPartSplit : fieldFilterPartSplitByType) {
            FilterType filterType = createFilterType(entityClass, repoClass, method, fieldFilterPartSplit, fieldSet);
            expectedCount += filterType.operator().getExpectedParameterCount();
        }
        return expectedCount;
    }

    private void checkParameterType(Method method, FilterType filterType, int startIndex, Class<?> repoClass) throws Exception {
        int expectedParamCount = filterType.operator().getExpectedParameterCount();
        Debugger.print("Check params for " + filterType.operator().name());
        for (int i = 0; i < expectedParamCount; i++) {
            int paramIndex = startIndex + i;
            Class<?> paramClass = method.getParameters()[paramIndex].getType();
            if (paramClass == null) {
                throw new MethodParameterNotFoundException(method, repoClass, (startIndex + expectedParamCount),
                        method.getParameterCount());
            }
            // Special check for regex, because it has only String or Pattern parameters
            if (filterType.operator() == FilterOperator.REGEX) {
                if (!GenericUtils.isTypeOf(String.class, paramClass) && !GenericUtils.isTypeOf(Pattern.class, paramClass)) {
                    throw new MethodInvalidRegexParameterException(method, repoClass, paramClass);
                }
                return;
            }
            Class<?> fieldClass = filterType.field().getType();
            if (filterType.operator() == FilterOperator.IN) {
                if (!GenericUtils.isTypeOf(List.class, paramClass)) {
                    throw new MethodMismatchingTypeException(method, repoClass, List.class, paramClass);
                }
                Class<?> listType = GenericUtils.getGenericTypeOfParameterList(method, paramIndex);
                if (!GenericUtils.isTypeOf(fieldClass, listType)) {
                    throw new MethodInvalidListParameterException(method, repoClass, fieldClass, listType);
                }
                return;
            }
            if (!GenericUtils.isTypeOf(fieldClass, paramClass)) {
                throw new MethodMismatchingTypeException(method, repoClass, fieldClass, paramClass);
            }
        }
    }

    protected <E> FilterType createFilterType(Class<E> entityClass, Class<?> repoClass, Method method,
                                              String operatorString, Set<Field> fieldSet) throws Exception {
        FilterOperator filterOperator = FilterOperator.parseFilterEndsWith(operatorString);
        if (filterOperator == null) {
            throw new MethodNoFilterOperatorException(method, repoClass);
        }
        String expectedField = filterOperator.removeOperatorFrom(operatorString);
        expectedField = expectedField.endsWith("Not") ? expectedField.replaceFirst("Not", "") : expectedField;
        Field field = findExpectedField(entityClass, expectedField, fieldSet);
        if (field == null) {
            throw new MethodFieldNotFoundException(expectedField, method, entityClass, repoClass);
        }
        return new FilterType(field, filterOperator);
    }

    private <E> Field findExpectedField(Class<E> entityClass, String expectedField, Set<Field> fieldSet) {
        for (Field field : fieldSet) {
            if (!field.getName().equalsIgnoreCase(expectedField)) {
                continue;
            }
            return field;
        }
        return null;
    }

    private <E> void checkSortOptions(Method method, Class<E> entityClass, Class<?> repoClass, Set<Field> fieldSet) throws Exception {
        String fieldName = null;
        if (method.isAnnotationPresent(SortBy.class)) {
            SortBy sortBy = method.getAnnotation(SortBy.class);
            fieldName = sortBy.field();
        }
        int parameterCount = method.getParameterCount();
        if (parameterCount > 0) {
            Class<?> lastParam = method.getParameterTypes()[parameterCount - 1];
            boolean hasAnySortAnnotation = method.isAnnotationPresent(Limit.class)
                    || method.isAnnotationPresent(Skip.class)
                    || method.isAnnotationPresent(SortBy.class)
                    || method.isAnnotationPresent(SortByArray.class);
            if (hasAnySortAnnotation && lastParam.isAssignableFrom(Sort.class)) {
                throw new MethodMixedSortException(method, repoClass);
            }
        }
        if (fieldName == null) {
            return;
        }
        Field field = findExpectedField(entityClass, fieldName, fieldSet);
        if (field == null) {
            throw new MethodSortFieldNotFoundException(fieldName, method, entityClass, repoClass);
        }
    }

    private <E> Set<Field> getAllFields(Class<E> typeClass) {
        Set<Field> fields = new HashSet<>();
        Class<?> clazz = typeClass;
        while (clazz != Object.class) {
            Field[] declaredFields = clazz.getDeclaredFields();
            clazz = clazz.getSuperclass();
            if (declaredFields.length == 0) {
                continue;
            }
            fields.addAll(Arrays.asList(declaredFields));
        }
        return fields;
    }

    private <E> Set<CompoundIndex> getAllEntityIndecies(Class<E> entityClass) {
        Set<CompoundIndex> compoundIndexList = new HashSet<>();
        Class<?> clazz = entityClass;
        while (clazz != Object.class) {
            CompoundIndex[] indexArray = clazz.getAnnotationsByType(CompoundIndex.class);
            clazz = clazz.getSuperclass();
            if (indexArray.length == 0) {
                continue;
            }
            compoundIndexList.addAll(Arrays.asList(indexArray));
        }
        return compoundIndexList;
    }
}
