/*
 * Copyright (C) 2020 ActiveJ LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.activej.serializer.impl;

import io.activej.codegen.ClassBuilder;
import io.activej.codegen.Expression;
import io.activej.codegen.Variable;
import io.activej.common.Utils;
import io.activej.serializer.CompatibilityLevel;
import io.activej.serializer.SerializerDef;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.Type;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

import static io.activej.codegen.Expressions.*;
import static io.activej.common.Preconditions.*;
import static io.activej.serializer.SerializerDef.StaticDecoders.IN;
import static io.activej.serializer.SerializerDef.StaticEncoders.*;
import static java.lang.reflect.Modifier.*;
import static java.util.Collections.singletonList;
import static org.objectweb.asm.Type.*;

public final class SerializerDefClass implements SerializerDef {

	private static final class FieldDef {
		private Field field;
		private Method method;
		private int versionAdded = -1;
		private int versionDeleted = -1;
		private SerializerDef serializer;

		public boolean hasVersion(int version) {
			if (versionAdded == -1 && versionDeleted == -1) {
				return true;
			}
			if (versionAdded != -1 && versionDeleted == -1) {
				return version >= versionAdded;
			}
			if (versionAdded == -1) {
				return version < versionDeleted;
			}
			if (versionAdded > versionDeleted) {
				return version < versionDeleted || version >= versionAdded;
			}
			if (versionAdded < versionDeleted) {
				return version >= versionAdded && version < versionDeleted;
			}
			throw new IllegalStateException("Added and deleted versions are equal");
		}

		public Class<?> getRawType() {
			if (field != null)
				return field.getType();
			if (method != null)
				return method.getReturnType();
			throw new AssertionError();
		}

		public Type getAsmType() {
			return getType(getRawType());
		}
	}

	private final Class<?> encodeType;
	private final Class<?> decodeType;

	private final LinkedHashMap<String, FieldDef> fields = new LinkedHashMap<>();

	private Constructor<?> constructor;
	private List<String> constructorParams;
	private Method factory;
	private List<String> factoryParams;
	private final Map<Method, List<String>> setters = new LinkedHashMap<>();

	private SerializerDefClass(Class<?> encodeType, Class<?> decodeType) {
		this.encodeType = encodeType;
		this.decodeType = decodeType;
	}

	public static SerializerDefClass of(@NotNull Class<?> type) {
		return new SerializerDefClass(type, type);
	}

	public static SerializerDefClass of(@NotNull Class<?> encodeType, @NotNull Class<?> decodeType) {
		checkArgument(encodeType.isAssignableFrom(decodeType), "Class should be assignable from %s", decodeType);
		return new SerializerDefClass(encodeType, decodeType);
	}

	public void addSetter(@NotNull Method method, @NotNull List<String> fields) {
		checkState(!decodeType.isInterface(), "Class should either implement an interface or be an interface");
		checkArgument(!isPrivate(method.getModifiers()), "Setter cannot be private: %s", method);
		checkArgument(method.getGenericParameterTypes().length == fields.size(), "Number of arguments of a method should match a size of list of fields");
		checkArgument(!setters.containsKey(method), "Setter has already been added");
		setters.put(method, fields);
	}

	public void setFactory(@NotNull Method methodFactory, @NotNull List<String> fields) {
		checkState(!decodeType.isInterface(), "Class should either implement an interface or be an interface");
		checkArgument(this.factory == null, "Factory is already set: %s", this.factory);
		checkArgument(!isPrivate(methodFactory.getModifiers()), "Factory cannot be private: %s", methodFactory);
		checkArgument(isStatic(methodFactory.getModifiers()), "Factory must be static: %s", methodFactory);
		checkArgument(methodFactory.getGenericParameterTypes().length == fields.size(), "Number of arguments of a method should match a size of list of fields");
		this.factory = methodFactory;
		this.factoryParams = fields;
	}

	public void setConstructor(@NotNull Constructor<?> constructor, @NotNull List<String> fields) {
		checkState(!decodeType.isInterface(), "Class should either implement an interface or be an interface");
		checkArgument(this.constructor == null, "Constructor is already set: %s", this.constructor);
		checkArgument(!isPrivate(constructor.getModifiers()), "Constructor cannot be private: %s", constructor);
		checkArgument(constructor.getGenericParameterTypes().length == fields.size(), "Number of arguments of a constructor should match a size of list of fields");
		this.constructor = constructor;
		this.constructorParams = fields;
	}

	public void addField(Field field, SerializerDef serializer, int added, int removed) {
		checkState(!decodeType.isInterface(), "Class should either implement an interface or be an interface");
		checkArgument(isPublic(field.getModifiers()), "Method should be public");
		String fieldName = field.getName();
		checkArgument(!fields.containsKey(fieldName), "Duplicate field '%s'", field);
		FieldDef fieldDef = new FieldDef();
		fieldDef.field = field;
		fieldDef.serializer = serializer;
		fieldDef.versionAdded = added;
		fieldDef.versionDeleted = removed;
		fields.put(fieldName, fieldDef);
	}

	public void addGetter(Method method, SerializerDef serializer, int added, int removed) {
		checkArgument(method.getGenericParameterTypes().length == 0, "Method should have 0 generic parameter types");
		checkArgument(isPublic(method.getModifiers()), "Method should be public");
		String fieldName = stripGet(method.getName(), method.getReturnType());
		checkArgument(!fields.containsKey(fieldName), "Duplicate field '%s'", method);
		FieldDef fieldDef = new FieldDef();
		fieldDef.method = method;
		fieldDef.serializer = serializer;
		fieldDef.versionAdded = added;
		fieldDef.versionDeleted = removed;
		fields.put(fieldName, fieldDef);
	}

	public void addMatchingSetters() {
		checkArgument(!decodeType.isInterface(), "Class should either implement an interface or be an interface");
		Set<String> usedFields = new HashSet<>();
		if (constructorParams != null) {
			usedFields.addAll(constructorParams);
		}
		if (factoryParams != null) {
			usedFields.addAll(factoryParams);
		}
		for (List<String> list : setters.values()) {
			usedFields.addAll(list);
		}
		for (Map.Entry<String, FieldDef> entry : fields.entrySet()) {
			String fieldName = entry.getKey();
			Method getter = entry.getValue().method;
			if (getter == null)
				continue;
			if (usedFields.contains(fieldName))
				continue;
			String setterName = "set" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
			try {
				Method setter = decodeType.getMethod(setterName, getter.getReturnType());
				if (!isPrivate(setter.getModifiers())) {
					addSetter(setter, singletonList(fieldName));
				}
			} catch (NoSuchMethodException e) {
				throw new RuntimeException(e);
			}
		}
	}

	@Override
	public void accept(Visitor visitor) {
		for (Map.Entry<String, FieldDef> entry : fields.entrySet()) {
			visitor.visit(entry.getKey(), entry.getValue().serializer);

		}
	}

	@Override
	public Set<Integer> getVersions() {
		Set<Integer> versions = new HashSet<>();
		for (FieldDef fieldDef : fields.values()) {
			if (fieldDef.versionAdded != -1)
				versions.add(fieldDef.versionAdded);
			if (fieldDef.versionDeleted != -1)
				versions.add(fieldDef.versionDeleted);
		}
		return versions;
	}

	@Override
	public Class<?> getEncodeType() {
		return encodeType;
	}

	@Override
	public Class<?> getDecodeType() {
		return decodeType;
	}

	private static String stripGet(String getterName, Class<?> type) {
		if (type == Boolean.TYPE || type == Boolean.class) {
			if (getterName.startsWith("is") && getterName.length() > 2) {
				return Character.toLowerCase(getterName.charAt(2)) + getterName.substring(3);
			}
		}
		if (getterName.startsWith("get") && getterName.length() > 3) {
			return Character.toLowerCase(getterName.charAt(3)) + getterName.substring(4);
		}
		return getterName;
	}

	@Override
	public Expression defineEncoder(StaticEncoders staticEncoders, Expression buf, Variable pos, Expression value, int version, CompatibilityLevel compatibilityLevel) {
		return fields.size() <= 1 ?
				encoder(staticEncoders, buf, pos, value, version, compatibilityLevel) :
				staticEncoders.define(encodeType, buf, pos, value,
						encoder(staticEncoders, BUF, POS, VALUE, version, compatibilityLevel));
	}

	@Override
	public Expression encoder(StaticEncoders staticEncoders, Expression buf, Variable pos, Expression value, int version, CompatibilityLevel compatibilityLevel) {
		List<Expression> list = new ArrayList<>();

		for (Map.Entry<String, FieldDef> entry : this.fields.entrySet()) {
			String fieldName = entry.getKey();
			FieldDef fieldDef = entry.getValue();
			if (!fieldDef.hasVersion(version)) continue;

			Class<?> fieldType = fieldDef.serializer.getEncodeType();

			if (fieldDef.field != null) {
				list.add(
						fieldDef.serializer.defineEncoder(staticEncoders, buf, pos, cast(property(value, fieldName), fieldType), version, compatibilityLevel));
			} else if (fieldDef.method != null) {
				list.add(
						fieldDef.serializer.defineEncoder(staticEncoders, buf, pos, cast(call(value, fieldDef.method.getName()), fieldType), version, compatibilityLevel));
			} else {
				throw new AssertionError();
			}
		}

		return sequence(list);
	}

	@Override
	public Expression defineDecoder(StaticDecoders staticDecoders, Expression in, int version, CompatibilityLevel compatibilityLevel) {
		return fields.size() <= 1 ?
				decoder(staticDecoders, in, version, compatibilityLevel) :
				staticDecoders.define(getDecodeType(), in,
						decoder(staticDecoders, IN, version, compatibilityLevel));
	}

	@Override
	public Expression decoder(StaticDecoders staticDecoders, Expression in, int version, CompatibilityLevel compatibilityLevel) {
		if (decodeType.isInterface()) {
			return deserializeInterface(staticDecoders, in, version, compatibilityLevel);
		}
		if (constructor == null && factory == null && setters.isEmpty()) {
			return deserializeClassSimple(staticDecoders, in, version, compatibilityLevel);
		}

		return let(Utils.of(() -> {
					List<Expression> fieldDeserializers = new ArrayList<>();
					for (FieldDef fieldDef : fields.values()) {
						if (!fieldDef.hasVersion(version)) continue;
						fieldDeserializers.add(
								fieldDef.serializer.defineDecoder(staticDecoders, in, version, compatibilityLevel));
					}
					return fieldDeserializers;
				}),
				fieldValues -> {
					Map<String, Expression> map = new HashMap<>();
					int i = 0;
					for (Map.Entry<String, FieldDef> entry : fields.entrySet()) {
						FieldDef fieldDef = entry.getValue();
						if (!fieldDef.hasVersion(version)) continue;
						map.put(entry.getKey(), cast(fieldValues.get(i++), fieldDef.getRawType()));
					}

					return let(factory == null ?
									callConstructor(decodeType, map, version) :
									callFactory(map, version),
							instance -> sequence(list -> {
								for (Map.Entry<Method, List<String>> entry : setters.entrySet()) {
									Method method = entry.getKey();
									boolean found = false;
									for (String fieldName : entry.getValue()) {
										FieldDef fieldDef = fields.get(fieldName);
										checkNotNull(fieldDef, "Field '%s' is not found in '%s'", fieldName, method);
										if (fieldDef.hasVersion(version)) {
											found = true;
											break;
										}
									}
									if (found) {
										Expression[] temp = new Expression[method.getParameterTypes().length];
										int j = 0;
										for (String fieldName : entry.getValue()) {
											FieldDef fieldDef = fields.get(fieldName);
											assert fieldDef != null;
											if (fieldDef.hasVersion(version)) {
												temp[j++] = map.get(fieldName);
											} else {
												temp[j++] = pushDefaultValue(fieldDef.getAsmType());
											}
										}
										list.add(call(instance, method.getName(), temp));
									}
								}

								for (Map.Entry<String, FieldDef> entry : fields.entrySet()) {
									String fieldName = entry.getKey();
									FieldDef fieldDef = entry.getValue();
									if (!fieldDef.hasVersion(version))
										continue;
									if (fieldDef.field == null || isFinal(fieldDef.field.getModifiers()))
										continue;
									Variable property = property(instance, fieldName);
									list.add(set(property, map.get(fieldName)));
								}

								list.add(instance);
							}));

				});
	}

	private Expression callFactory(Map<String, Expression> map, int version) {
		Expression[] param = new Expression[factoryParams.size()];
		int i = 0;
		for (String fieldName : factoryParams) {
			FieldDef fieldDef = fields.get(fieldName);
			checkNotNull(fieldDef, "Field '%s' is not found in '%s'", fieldName, factory);
			if (fieldDef.hasVersion(version)) {
				param[i++] = map.get(fieldName);
			} else {
				param[i++] = pushDefaultValue(fieldDef.getAsmType());
			}
		}
		return staticCall(factory.getDeclaringClass(), factory.getName(), param);
	}

	private Expression callConstructor(Class<?> targetType, Map<String, Expression> map, int version) {
		Expression[] param;
		if (constructorParams == null) {
			param = new Expression[0];
			return constructor(targetType, param);
		}
		param = new Expression[constructorParams.size()];

		int i = 0;
		for (String fieldName : constructorParams) {
			FieldDef fieldDef = fields.get(fieldName);
			checkNotNull(fieldDef, "Field '%s' is not found in '%s'", fieldName, constructor);
			if (fieldDef.hasVersion(version)) {
				param[i++] = map.get(fieldName);
			} else {
				param[i++] = pushDefaultValue(fieldDef.getAsmType());
			}

		}
		return constructor(targetType, param);
	}

	private Expression deserializeInterface(StaticDecoders staticDecoders, Expression in,
			int version, CompatibilityLevel compatibilityLevel) {
		ClassBuilder<?> classBuilder = staticDecoders.buildClass(decodeType);
		for (Map.Entry<String, FieldDef> entry : fields.entrySet()) {
			String fieldName = entry.getKey();

			Method method = checkNotNull(entry.getValue().method);

			classBuilder
					.withField(fieldName, method.getReturnType())
					.withMethod(method.getName(), property(self(), fieldName));
		}

		Class<?> newClass = classBuilder.build();

		return let(
				constructor(newClass),
				instance -> sequence(expressions -> {
					for (Map.Entry<String, FieldDef> entry : fields.entrySet()) {
						FieldDef fieldDef = entry.getValue();
						if (!fieldDef.hasVersion(version))
							continue;
						Variable property = property(instance, entry.getKey());

						Expression expression =
								fieldDef.serializer.defineDecoder(staticDecoders, in, version, compatibilityLevel);
						expressions.add(set(property, expression));
					}
					expressions.add(instance);
				}));
	}

	private Expression deserializeClassSimple(StaticDecoders staticDecoders, Expression in,
			int version, CompatibilityLevel compatibilityLevel) {
		return let(
				constructor(decodeType),
				instance ->
						sequence(expressions -> {
							for (Map.Entry<String, FieldDef> entry : fields.entrySet()) {
								FieldDef fieldDef = entry.getValue();
								if (!fieldDef.hasVersion(version)) continue;

								expressions.add(
										set(property(instance, entry.getKey()),
												fieldDef.serializer.defineDecoder(staticDecoders, in, version, compatibilityLevel)));
							}
							expressions.add(instance);
						}));
	}

	private Expression pushDefaultValue(Type type) {
		switch (type.getSort()) {
			case BOOLEAN:
				return value(false);
			case CHAR:
				return value((char) 0);
			case BYTE:
				return value((byte) 0);
			case SHORT:
				return value((short) 0);
			case INT:
				return value(0);
			case Type.LONG:
				return value(0L);
			case Type.FLOAT:
				return value(0f);
			case Type.DOUBLE:
				return value(0d);
			case ARRAY:
			case OBJECT:
				return nullRef(type);
			default:
				throw new IllegalArgumentException("Unsupported type " + type);
		}
	}

	@Override
	public String toString() {
		return "SerializerDefClass{" + encodeType.getSimpleName() + '}';
	}
}
