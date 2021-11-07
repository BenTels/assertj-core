/*
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * Copyright 2012-2021 the original author or authors.
 */
package org.assertj.core.api.recursive.assertion;

import org.assertj.core.api.recursive.FieldLocation;
import org.assertj.core.internal.Objects;
import org.assertj.core.util.Arrays;
import org.assertj.core.util.introspection.FieldSupport;
import org.assertj.core.util.introspection.PropertyOrFieldSupport;

import java.util.*;
import java.util.function.Predicate;

import static java.lang.String.format;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.util.Lists.list;
import static org.assertj.core.util.Sets.newHashSet;

public class RecursiveAssertionDriver {

  private static final String INDEX_FORMAT = "[%d]";
  private static final String KEY_FORMAT = "KEY[%s]";
  private static final String VALUE_FORMAT = "VAL[%s]";
  private static final String OPTIONAL_VALUE = "VAL";

  private final Set<String> markedBlackSet = newHashSet();
  private final List<FieldLocation> fieldsThatFailedTheAssertion = list();
  private final RecursiveAssertionConfiguration configuration;

  public RecursiveAssertionDriver(RecursiveAssertionConfiguration configuration) {
    this.configuration = configuration;
  }

  public List<FieldLocation> assertOverObjectGraph(Predicate<Object> predicate, Object graphNode) {
    assertRecursively(predicate, graphNode, graphNode.getClass(), FieldLocation.rootFieldLocation());
    return Collections.unmodifiableList(fieldsThatFailedTheAssertion);
  }

  public void reset() {
    markedBlackSet.clear();
    fieldsThatFailedTheAssertion.clear();
  }

  private void assertRecursively(Predicate<Object> predicate, Object node, Class<?> nodeType, FieldLocation fieldLocation) {
    if (nodeMustBeIgnored(node, nodeType, fieldLocation)) return;

    boolean nodeWasAlreadyBlack = markNodeAsBlack(node);
    if (nodeWasAlreadyBlack) return;

    checkPoliciesAndAssert(predicate, node, nodeType, fieldLocation);
    // TODO 3: Check recursive conditions
    // TODO 4: Check for map/collections/arrays/optionals
    // TODO 5: Make the recursive call for all applicable fields
    recurseIntoFieldsOfCurrentNode(predicate, node, nodeType, fieldLocation);
  }

  private boolean nodeMustBeIgnored(Object node, Class<?> nodeType, FieldLocation fieldLocation) {
    return node_is_null_and_we_are_ignoring_those(node)
           || node_is_primitive_and_we_are_ignoring_those(nodeType)
           || node_is_empty_optional_and_we_are_ignoring_those(node, nodeType)
           || node_is_being_ignored_by_name_or_name_patten(fieldLocation)
           || node_is_being_ignored_by_type(nodeType);
  }

  private boolean node_is_null_and_we_are_ignoring_those(Object node) {
    return node == null && configuration.getIgnoreAllActualNullFields();
  }

  private boolean node_is_primitive_and_we_are_ignoring_those(Class<?> nodeType) {
    return nodeType.isPrimitive() && !configuration.getAssertOverPrimitiveFields();
  }

  private boolean node_is_empty_optional_and_we_are_ignoring_those(Object node, Class<?> nodeType) {
    return configuration.getIgnoreAllActualEmptyOptionalFields()
           && isAnEmptyOptional(node, nodeType);
  }

  private boolean isAnEmptyOptional(Object node, Class<?> nodeType) {
    return (isOptional(nodeType) && !((Optional<?>)node).isPresent())
      || (isOptionalInt(nodeType) && !((OptionalInt)node).isPresent())
      || (isOptionalDouble(nodeType) && !((OptionalDouble)node).isPresent())
      || (isOptionalLong(nodeType) && !((OptionalLong)node).isPresent());
  }

  private boolean node_is_being_ignored_by_name_or_name_patten(FieldLocation fieldLocation) {
    return configuration.matchesAnIgnoredField(fieldLocation)
           || configuration.matchesAnIgnoredFieldRegex(fieldLocation);
  }

  private boolean node_is_being_ignored_by_type(Class<?> nodeType) {
    return configuration.getIgnoredTypes().contains(nodeType);
  }

  private void checkPoliciesAndAssert(Predicate<Object> predicate, Object node, Class<?> nodeType, FieldLocation fieldLocation) {
    if (policyDoesNotForbidAssertingOverNode(nodeType)) {
      doTheActualAssertionAndRegisterInCaseOfFailure(predicate, node, fieldLocation);
    }
  }

  private boolean policyDoesNotForbidAssertingOverNode(Class<?> nodeType) {
    boolean policyForbidsAsserting = node_is_a_collection_and_policy_is_to_ignore_container(nodeType);
    policyForbidsAsserting = policyForbidsAsserting || node_is_a_map_and_policy_is_to_ignore_container(nodeType);
    return !policyForbidsAsserting;
  }

  private boolean node_is_a_collection_and_policy_is_to_ignore_container(Class<?> nodeType) {
    boolean nodeIsCollection = isCollection(nodeType) || isArray(nodeType);
    boolean policyIsIgnoreContainer =
      configuration.getCollectionAssertionPolicy() == RecursiveAssertionConfiguration.CollectionAssertionPolicy.ELEMENTS_ONLY;
    return policyIsIgnoreContainer && nodeIsCollection;
  }

  private boolean node_is_a_map_and_policy_is_to_ignore_container(Class<?> nodeType) {
    return configuration.getMapAssertionPolicy() == RecursiveAssertionConfiguration.MapAssertionPolicy.VALUES_ONLY
           && isMap(nodeType);
  }

  private void recurseIntoFieldsOfCurrentNode(Predicate<Object> predicate, Object node, Class<?> nodeType,
                                              FieldLocation fieldLocation) {
    if (node_is_a_special_type_which_requires_special_treatment(nodeType)) {
      if (policy_is_to_recurse_over_special_types(nodeType)) {
        doRecursionForSpecialTypes(predicate, node, nodeType, fieldLocation);
      }
    } else if (nodeShouldBeRecursedInto(node)) {
      findFieldsOfCurrentNodeAndDoRecursiveCall(predicate, node, fieldLocation);
    }
  }

  private boolean node_is_a_special_type_which_requires_special_treatment(Class<?> nodeType) {
    return isCollection(nodeType)
           || isMap(nodeType)
           || isArray(nodeType)
           || node_is_an_optional_requiring_special_treatment(nodeType);
  }

  private boolean policy_is_to_recurse_over_special_types(Class<?> nodeType) {
    boolean recurseOverCollection = (isCollection(nodeType)
                                     || isArray(nodeType))
                                    && (configuration.getCollectionAssertionPolicy()
                                        != RecursiveAssertionConfiguration.CollectionAssertionPolicy.COLLECTION_OBJECT_ONLY);
    boolean recurseOverMap = isMap(nodeType)
                             && configuration.getMapAssertionPolicy()
                                != RecursiveAssertionConfiguration.MapAssertionPolicy.MAP_OBJECT_ONLY;
    return recurseOverCollection || recurseOverMap || node_is_an_optional_requiring_special_treatment(nodeType);
  }

  private boolean node_is_an_optional_requiring_special_treatment(Class<?> nodeType) {
    return configuration.isSkipJavaLibraryTypeObjects() && isOptionalType(nodeType);
  }

  private void doRecursionForSpecialTypes(Predicate<Object> predicate, Object node, Class<?> nodeType,
                                          FieldLocation fieldLocation) {
    if (isCollection(nodeType)) {
      recurseIntoCollection(predicate, (Collection<?>) node, fieldLocation);
    }
    if (isArray(nodeType)) {
      recurseIntoArray(predicate, node, nodeType, fieldLocation);
    }
    if (isMap(nodeType)) {
      recurseIntoMap(predicate, (Map<?, ?>) node, fieldLocation);
    }
    if (isOptional(nodeType)) {
      recurseIntoOptionalValue(predicate, (Optional<?>) node, fieldLocation);
    }
    if (isOptionalInt(nodeType)) {
      recurseIntoOptionalIntValue(predicate, (OptionalInt) node, fieldLocation);
    }
    if (isOptionalDouble(nodeType)) {
      recurseIntoOptionalDoubleValue(predicate, (OptionalDouble) node, fieldLocation);
    }
    if (isOptionalLong(nodeType)) {
      recurseIntoOptionalLongValue(predicate, (OptionalLong) node, fieldLocation);
    }
  }

  private void recurseIntoCollection(Predicate<Object> predicate, Collection<?> node, FieldLocation fieldLocation) {
    int idx = 0;
    for (Object o : node) {
      assertRecursively(predicate, o, o != null ? o.getClass() : Object.class,
                        fieldLocation.field(format(INDEX_FORMAT, idx)));
      idx++;
    }
  }

  private void recurseIntoArray(Predicate<Object> predicate, Object node, Class<?> nodeType, FieldLocation fieldLocation) {
    Class<?> arrayType = nodeType.getComponentType();
    Object[] arr = Arrays.asObjectArray(node);
    for (int i = 0; i < arr.length; i++) {
      assertRecursively(predicate, arr[i], arrayType,
                        fieldLocation.field(format(INDEX_FORMAT, i)));
    }
  }

  private void recurseIntoMap(Predicate<Object> predicate, Map<?, ?> node, FieldLocation fieldLocation) {
    // If we are here, we can assume the policy is not MAP_OBJECT_ONLY
    // For both policies VALUES_ONLY and MAP_OBJECT_AND_ENTRIES we have to recurse over
    // the values.
    recurseIntoMapValues(predicate, node, fieldLocation);
    if (configuration.getMapAssertionPolicy() == RecursiveAssertionConfiguration.MapAssertionPolicy.MAP_OBJECT_AND_ENTRIES) {
      recurseIntoMapKeys(predicate, node, fieldLocation);
    }
  }

  private void recurseIntoMapValues(Predicate<Object> predicate, Map<?, ?> currentNode, FieldLocation fieldLocation) {
    currentNode.values().forEach(nextNode -> recurseIntoMapElement(predicate, fieldLocation, nextNode, VALUE_FORMAT));
  }

  private void recurseIntoMapKeys(Predicate<Object> predicate, Map<?, ?> currentNode, FieldLocation fieldLocation) {
    currentNode.keySet().forEach(nextNode -> recurseIntoMapElement(predicate, fieldLocation, nextNode, KEY_FORMAT));
  }

  private void recurseIntoMapElement(Predicate<Object> predicate, FieldLocation fieldLocation, Object nextNode,
                                     String msgFormat) {
    Class<?> nextNodeType = nextNode != null ? nextNode.getClass() : Object.class;
    String nextNodeFieldName = nextNode != null ? nextNode.toString() : "null";
    assertRecursively(predicate,
                      nextNode,
                      nextNodeType,
                      fieldLocation.field(format(msgFormat, nextNodeFieldName)));
  }

  private void recurseIntoOptionalValue(Predicate<Object> predicate, Optional<?> node, FieldLocation fieldLocation) {
    Object nextNode = null;
    Class<?> nextNodeType = Object.class;
    if (node.isPresent()) {
      nextNode = node.get();
      nextNodeType = nextNode.getClass();
    }
    assertRecursively(predicate, nextNode, nextNodeType, fieldLocation.field(OPTIONAL_VALUE));
  }

  private void recurseIntoOptionalIntValue(Predicate<Object> predicate, OptionalInt node, FieldLocation fieldLocation) {
    if (node.isPresent()) assertRecursively(predicate, node.getAsInt(), int.class, fieldLocation.field(OPTIONAL_VALUE));
    /* Note (bzt): At this time I am electing not to make a recursive call in case of an empty OptionalInt, because I
     * can't decide on what to pass as a node value.
     */
  }

  private void recurseIntoOptionalDoubleValue(Predicate<Object> predicate, OptionalDouble node, FieldLocation fieldLocation) {
    if (node.isPresent()) assertRecursively(predicate, node.getAsDouble(), double.class, fieldLocation.field(OPTIONAL_VALUE));
    /* Note (bzt): At this time I am electing not to make a recursive call in case of an empty OptionalDouble, because I
     * can't decide on what to pass as a node value.
     */
  }

  private void recurseIntoOptionalLongValue(Predicate<Object> predicate, OptionalLong node, FieldLocation fieldLocation) {
    if (node.isPresent()) assertRecursively(predicate, node.getAsLong(), long.class, fieldLocation.field(OPTIONAL_VALUE));
    /* Note (bzt): At this time I am electing not to make a recursive call in case of an empty OptionalLong, because I
     * can't decide on what to pass as a node value.
     */
  }

  private boolean nodeShouldBeRecursedInto(Object node) {
    boolean nodeShouldBeRecursedInto = node != null;
    nodeShouldBeRecursedInto = nodeShouldBeRecursedInto && !node_is_a_jcl_type_and_we_skip_those(node);
    return nodeShouldBeRecursedInto;
  }

  private boolean node_is_a_jcl_type_and_we_skip_those(Object node) {
    boolean skipJCLTypes = configuration.isSkipJavaLibraryTypeObjects();
    boolean isJCLType = node.getClass().getCanonicalName().startsWith("java.")
                        || node.getClass().getCanonicalName().startsWith("javax.");
    return isJCLType && skipJCLTypes;
  }

  private void findFieldsOfCurrentNodeAndDoRecursiveCall(Predicate<Object> predicate, Object node, FieldLocation fieldLocation) {
    Set<String> namesOfFieldsInNode = Objects.getFieldsNames(node.getClass());
    namesOfFieldsInNode.stream()
                       .map(name -> tuple(name, PropertyOrFieldSupport.EXTRACTION.getSimpleValue(name, node),
                                          FieldSupport.getFieldType(name, node)))
                       .forEach(tuple -> {
                         String fieldName = tuple.getByIndexAndType(0, String.class);
                         Object nextNodeValue = tuple.getByIndexAndType(1, Object.class);
                         Class<?> declaredFieldType = tuple.getByIndexAndType(2, Class.class);
                         Class<?> nextNodeType = nextNodeValue != null ? nextNodeValue.getClass() : declaredFieldType;
                         if (declaredFieldType.isPrimitive()) nextNodeType = declaredFieldType;
                         assertRecursively(predicate, nextNodeValue, nextNodeType, fieldLocation.field(fieldName));
                       });
  }

  private void doTheActualAssertionAndRegisterInCaseOfFailure(Predicate<Object> predicate, Object node,
                                                              FieldLocation fieldLocation) {
    if (!predicate.test(node)) {
      fieldsThatFailedTheAssertion.add(fieldLocation);
    }
  }

  private boolean markNodeAsBlack(Object node) {
    // Cannot mark null nodes, so just lie and say marking succeeded...
    if (node == null) return false;

    String objectId = identityToString(node);
    return !markedBlackSet.add(objectId);
  }

  /*
   * This is taken verbatim from org.apache.commons.lang3.ObjectUtils .
   *
   * It would be much cleaner if we would be allowed to use ObjectUtils directly.
   */
  private String identityToString(final Object object) {
    if (object == null) {
      return null;
    }
    final String name = object.getClass().getName();
    final String hexString = Integer.toHexString(System.identityHashCode(object));
    final StringBuilder builder = new StringBuilder(name.length() + 1 + hexString.length());
    // @formatter:off
    builder.append(name)
           .append('@')
           .append(hexString);
    // @formatter:on
    return builder.toString();
  }

  private boolean isCollection(Class<?> nodeType) {
    return Collection.class.isAssignableFrom(nodeType);
  }

  private boolean isArray(Class<?> nodeType) {
    return nodeType.isArray();
  }

  private boolean isMap(Class<?> nodeType) {
    return Map.class.isAssignableFrom(nodeType);
  }

  private boolean isOptional(Class<?> nodeType) {
    return Optional.class.isAssignableFrom(nodeType);
  }


  private boolean isOptionalLong(Class<?> nodeType) {
    return OptionalLong.class.isAssignableFrom(nodeType);
  }

  private boolean isOptionalInt(Class<?> nodeType) {
    return OptionalInt.class.isAssignableFrom(nodeType);
  }

  private boolean isOptionalDouble(Class<?> nodeType) {
    return OptionalDouble.class.isAssignableFrom(nodeType);
  }

  private boolean isOptionalType(Class<?> nodeType) {
    return isOptional(nodeType)
      || isOptionalInt(nodeType)
      || isOptionalDouble(nodeType)
      || isOptionalLong(nodeType);
  }

}