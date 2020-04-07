/**
 * Jooby https://jooby.io
 * Apache License Version 2.0 https://jooby.io/LICENSE.txt
 * Copyright 2014 Edgar Espina
 */
package io.jooby.internal.openapi;

import io.jooby.Context;
import io.jooby.MediaType;
import io.jooby.Router;
import io.jooby.Session;
import io.jooby.annotations.ContextParam;
import io.jooby.annotations.CookieParam;
import io.jooby.annotations.FormParam;
import io.jooby.annotations.GET;
import io.jooby.annotations.HeaderParam;
import io.jooby.annotations.Path;
import io.jooby.annotations.PathParam;
import io.jooby.annotations.QueryParam;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.ParameterNode;

import javax.inject.Named;
import javax.inject.Provider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.jooby.internal.openapi.AsmUtils.findAnnotationByType;
import static io.jooby.internal.openapi.TypeFactory.KT_FUN_0;
import static io.jooby.internal.openapi.TypeFactory.KT_KLASS;
import static java.util.Collections.singletonList;

public class AnnotationParser {
  enum ParamType {
    CONTEXT {
      @Override public Class[] annotations() {
        return new Class[]{ContextParam.class};
      }
    },
    HEADER {
      @Override public Class[] annotations() {
        return new Class[]{HeaderParam.class, javax.ws.rs.HeaderParam.class};
      }

      @Override public void setIn(Parameter parameter) {
        parameter.setIn("header");
      }
    },
    COOKIE {
      @Override public Class[] annotations() {
        return new Class[]{CookieParam.class, javax.ws.rs.CookieParam.class};
      }

      @Override public void setIn(Parameter parameter) {
        parameter.setIn("cookie");
      }
    },
    PATH {
      @Override public Class[] annotations() {
        return new Class[]{PathParam.class, javax.ws.rs.PathParam.class};
      }

      @Override public void setIn(Parameter parameter) {
        parameter.setIn("path");
        parameter.setRequired(true);
      }
    },
    QUERY {
      @Override public Class[] annotations() {
        return new Class[]{QueryParam.class, javax.ws.rs.QueryParam.class};
      }

      @Override public void setIn(Parameter parameter) {
        parameter.setIn("query");
      }
    },

    FORM {
      @Override public Class[] annotations() {
        return new Class[]{FormParam.class, javax.ws.rs.FormParam.class};
      }

      @Override public void setIn(Parameter parameter) {
        parameter.setIn("form");
      }
    },

    BODY {
      @Override public Class[] annotations() {
        return new Class[0];
      }
    };

    public abstract Class[] annotations();

    public boolean matches(String annotationType) {
      return Stream.of(annotations())
          .anyMatch(t -> t.getName().equals(annotationType));
    }

    public void setIn(Parameter parameter) {
    }

    public Optional<String> getHttpName(List<AnnotationNode> annotations) {
      List<Class> names = new ArrayList<>(Arrays.asList(annotations()));
      names.add(Named.class);
      return annotations.stream()
          .filter(a ->
              names.stream().anyMatch(c -> Type.getDescriptor(c).equals(a.desc))
          )
          .map(a -> {
            if (a.values != null) {
              for (int i = 0; i < a.values.size(); i++) {
                if (a.values.get(i).equals("value")) {
                  Object value = a.values.get(i + 1);
                  if (value != null && value.toString().trim().length() > 0) {
                    return value.toString().trim();
                  }
                }
              }
            }
            return null;
          })
          .filter(Objects::nonNull)
          .findFirst();
    }

    public static ParamType find(List<AnnotationNode> annotations) {
      if (annotations != null) {
        for (AnnotationNode annotation : annotations) {
          String annotationType = Type.getType(annotation.desc).getClassName();
          for (ParamType paramType : values()) {
            if (paramType.matches(annotationType)) {
              return paramType;
            }
          }
        }
      }
      return BODY;
    }
  }

  static final String PACKAGE = GET.class.getPackage().getName();

  static final Set<String> IGNORED_PARAM_TYPE = Arrays.asList(
      Context.class.getName(),
      Session.class.getName(),
      "java.util.Optional<" + Session.class.getName() + ">",
      "kotlin.coroutines.Continuation"
  ).stream().collect(Collectors.toSet());

  public static List<OperationExt> parse(ParserContext ctx, String prefix,
      Signature signature, MethodInsnNode node) {
    if (signature.matches(Class.class) ||
        signature.matches(Class.class, Provider.class)
        || signature.matches(KT_KLASS)
        || signature.matches(KT_KLASS, KT_FUN_0)) {
      Type type = InsnSupport.prev(node)
          .filter(e -> e instanceof LdcInsnNode && ((LdcInsnNode) e).cst instanceof Type)
          .findFirst()
          .map(e -> (Type) ((LdcInsnNode) e).cst)
          .orElseThrow(() -> new IllegalStateException(
              "Mvc class not found: " + InsnSupport.toString(node)));
      return parse(ctx, prefix, type);
    } else if (signature.matches(Object.class)) {
      AbstractInsnNode previous = node.getPrevious();
      if (previous instanceof MethodInsnNode) {
        MethodInsnNode methodInsnNode = (MethodInsnNode) previous;
        if (methodInsnNode.getOpcode() == Opcodes.INVOKESPECIAL) {
          // mvc(new Controller(...));
          Type type = Type.getObjectType(methodInsnNode.owner);
          return parse(ctx, prefix, type);
        } else {
          // mvc(some.myController());
          Type type = Type.getReturnType(methodInsnNode.desc);
          return parse(ctx, prefix, type);
        }
      }
    }
    return Collections.emptyList();
  }

  private static List<OperationExt> parse(ParserContext ctx, String prefix, Type type) {
    List<OperationExt> result = new ArrayList<>();
    ClassNode classNode = ctx.classNode(type);
    Collection<MethodNode> methods = methods(ctx, classNode).values();
    for (MethodNode method : methods) {
      ctx.debugHandler(method);
      result.addAll(routerMethod(ctx, prefix, classNode, method));
    }
    result.forEach(it -> it.setController(classNode));
    return result;
  }

  private static Map<String, MethodNode> methods(ParserContext ctx, ClassNode node) {
    Map<String, MethodNode> methods = new LinkedHashMap<>();
    if (node.superName != null && !node.superName.equals(TypeFactory.OBJECT.getInternalName())) {
      methods.putAll(methods(ctx, ctx.classNode(Type.getObjectType(node.superName))));
    }
    node.methods.stream().filter(AnnotationParser::isRouter).forEach(it -> {
      String signature = it.name + it.desc.substring(0, it.desc.indexOf(')') + 1);
      methods.put(signature, it);
    });
    return methods;
  }

  private static List<OperationExt> routerMethod(ParserContext ctx, String prefix,
      ClassNode classNode, MethodNode method) {
    List<OperationExt> result = new ArrayList<>();

    AtomicReference<RequestBodyExt> requestBody = new AtomicReference<>();
    List<ParameterExt> arguments = routerArguments(ctx, method, requestBody::set);
    ResponseExt response = returnTypes(method);

    for (String httpMethod : httpMethod(method.visibleAnnotations)) {
      for (String pattern : httpPattern(ctx, classNode, method, httpMethod)) {
        OperationExt operation = new OperationExt(
            method,
            httpMethod,
            RoutePath.path(prefix, pattern),
            arguments,
            response
        );
        operation.setOperationId(method.name);
        if (isDeprecated(method.visibleAnnotations)) {
          operation.setDeprecated(true);
        }
        Optional.ofNullable(requestBody.get()).ifPresent(operation::setRequestBody);

        result.add(operation);
      }
    }

    return result;
  }

  private static boolean isDeprecated(List<AnnotationNode> annotations) {
    if (annotations != null) {
      return annotations.stream()
          .anyMatch(a -> a.desc.equals(Type.getDescriptor(Deprecated.class)));
    }
    return false;
  }

  private static ResponseExt returnTypes(MethodNode method) {
    Signature signature = Signature.create(method);
    String desc = Optional.ofNullable(method.signature).orElse(method.desc);
    String continuationType = "Lkotlin/coroutines/Continuation;";
    if (signature.matches(Type.getType(continuationType)) && method.signature != null) {
      desc = method.signature.substring(1, method.signature.indexOf(')'));
      // Lkotlin/coroutines/Continuation<-Ljava/lang/String;>;
      desc = desc.substring(continuationType.length() + 1, desc.length() - 2);
    }
    int i = desc.indexOf(')');
    if (i > 0) {
      desc = desc.substring(i + 1);
    }
    ResponseExt rrt = new ResponseExt();
    rrt.setJavaTypes(Collections.singletonList(ASMType.parse(desc)));
    return rrt;
  }

  private static List<ParameterExt> routerArguments(ParserContext ctx, MethodNode method,
      Consumer<RequestBodyExt> requestBody) {
    List<ParameterExt> result = new ArrayList<>();
    Map<String, Schema> form = new LinkedHashMap<>();
    List<String> requiredFormFields = new ArrayList<>();
    if (method.parameters != null) {
      for (int i = 0; i < method.parameters.size(); i++) {
        ParameterNode parameter = method.parameters.get(i);

        List<String> javaName;
        if (parameter.name.equals("continuation") && i == method.parameters.size() - 1) {
          javaName = Arrays.asList(parameter.name, "$" + parameter.name);
        } else {
          javaName = singletonList(parameter.name);
        }
        /** Java Type: */
        LocalVariableNode variable = method.localVariables.stream()
            .filter(var -> javaName.contains(var.name))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "Parameter type not found on method: " + method.name + ", parameter: "
                    + parameter.name));
        String javaType = variable.signature == null
            ? ASMType.parse(variable.desc)
            : ASMType.parse(variable.signature);

        if (IGNORED_PARAM_TYPE.contains(javaType)) {
          continue;
        }

        /** HTTP Type: */
        List<AnnotationNode> annotations;
        if (method.visibleParameterAnnotations != null
            && i < method.visibleParameterAnnotations.length) {
          annotations = method.visibleParameterAnnotations[i];
        } else {
          annotations = Collections.emptyList();
        }
        ParamType paramType = ParamType.find(annotations);

        /** Required: */
        boolean required = isPrimitive(javaType)
            ? true
            : !isNullable(method, i);//!javaType.startsWith("java.util.Optional");

        if (paramType == ParamType.BODY) {
          RequestBodyExt body = new RequestBodyExt();
          body.setRequired(required);
          body.setJavaType(javaType);
          requestBody.accept(body);
        } else if (paramType == ParamType.FORM) {
          String field = paramType.getHttpName(annotations).orElse(parameter.name);
          if (required) {
            requiredFormFields.add(field);
          }
          Schema schemaProperty = ctx.schema(javaType);
          if (ctx.schemaRef(javaType).isPresent()) {
            // form bean (i.e. single body)
            RequestBodyExt body = new RequestBodyExt();
            body.setContentType(MediaType.MULTIPART_FORMDATA);
            if (required) {
              body.setRequired(true);
            }
            body.setJavaType(javaType);
            requestBody.accept(body);
          } else {
            // single property
            form.put(field, schemaProperty);
          }
        } else {
          ParameterExt argument = new ParameterExt();
          argument.setName(paramType.getHttpName(annotations).orElse(parameter.name));
          argument.setJavaType(javaType);
          paramType.setIn(argument);
          if (required) {
            argument.setRequired(true);
          }
          result.add(argument);
        }
      }
    }
    if (form.size() > 0) {
      Schema schema = new ObjectSchema();
      schema.setProperties(form);
      if (requiredFormFields.size() > 0) {
        schema.setRequired(requiredFormFields);
      }

      io.swagger.v3.oas.models.media.MediaType mediaType = new io.swagger.v3.oas.models.media.MediaType();
      mediaType.setSchema(schema);

      Content content = new Content();
      content.addMediaType(MediaType.MULTIPART_FORMDATA, mediaType);

      RequestBodyExt body = new RequestBodyExt();
      body.setContent(content);

      requestBody.accept(body);
    }
    return result;
  }

  private static boolean isNullable(MethodNode method, int paramIndex) {
    if (paramIndex < method.invisibleAnnotableParameterCount) {
      List<AnnotationNode> annotations = method.invisibleParameterAnnotations[paramIndex];
      return annotations.stream()
          .anyMatch(a -> a.desc.equals("Lorg/jetbrains/annotations/Nullable;"));
    }
    return true;
  }

  private static boolean isPrimitive(String javaType) {
    switch (javaType) {
      case "boolean":
      case "byte":
      case "char":
      case "short":
      case "int":
      case "float":
      case "double":
      case "long":
        return true;
    }
    return false;
  }

  private static List<String> httpPattern(ParserContext ctx, ClassNode classNode, MethodNode method,
      String httpMethod) {
    List<String> patterns = new ArrayList<>();

    List<String> rootPattern = httpPattern(httpMethod, null, classNode.visibleAnnotations);
    while (rootPattern.isEmpty() && classNode.superName != null) {
      classNode = ctx.classNode(Type.getObjectType(classNode.superName));
      rootPattern = httpPattern(httpMethod, null, classNode.visibleAnnotations);
    }

    if (rootPattern.isEmpty()) {
      rootPattern = Collections.singletonList("/");
    }

    for (String prefix : rootPattern) {
      patterns.addAll(httpPattern(httpMethod, prefix, method.visibleAnnotations));
    }

    if (patterns.size() == 0) {
      patterns.add("/");
    }

    return patterns;
  }

  private static List<String> httpPattern(String httpMethod, String prefix,
      List<AnnotationNode> annotations) {
    List<String> patterns = new ArrayList<>();
    if (annotations != null) {

      List<Map<String, Object>> values = findAnnotationByType(annotations,
          Arrays.asList(PACKAGE + "." + httpMethod)).stream()
          .flatMap(annotation -> Stream.of(annotation)
              .map(AsmUtils::toMap)
          )
          .filter(m -> !m.isEmpty())
          .collect(Collectors.toList());

      if (values.isEmpty()) {
        values = findAnnotationByType(annotations, Arrays.asList(Path.class.getName())).stream()
            .flatMap(annotation -> Stream.of(annotation)
                .map(AsmUtils::toMap)
            )
            .filter(m -> !m.isEmpty())
            .collect(Collectors.toList());

        if (values.isEmpty()) {
          values = findAnnotationByType(annotations,
              Arrays.asList(javax.ws.rs.Path.class.getName())).stream()
              .flatMap(annotation -> Stream.of(annotation)
                  .map(AsmUtils::toMap)
              )
              .filter(m -> !m.isEmpty())
              .collect(Collectors.toList());
        }
      }

      for (Map<String, Object> map : values) {
        Object value = map.getOrDefault("value", Collections.emptyList());
        if (!(value instanceof Collection)) {
          value = Arrays.asList(value);
        }
        ((List) value)
            .forEach(v -> patterns.add(RoutePath.path(prefix, v.toString())));
      }
    }
    if (prefix != null && patterns.isEmpty()) {
      patterns.add(RoutePath.path(prefix, null));
    }
    return patterns;
  }

  private static List<String> httpMethod(List<AnnotationNode> annotations) {
    List<String> methods = findAnnotationByType(annotations, httpMethods()).stream()
        .map(n -> Type.getType(n.desc).getClassName())
        .map(name -> {
          String[] names = name.split("\\.");
          return names[names.length - 1];
        })
        .distinct()
        .collect(Collectors.toList());
    if (methods.size() == 1 && methods.contains("Path")) {
      return Arrays.asList(Router.GET);
    }
    methods.remove("Path");
    return methods;
  }

  private static boolean isRouter(MethodNode node) {
    if (node.visibleAnnotations != null) {
      // Jooby annotations
      List<String> annotationTypes = httpMethods().stream()
          .map(classname -> Type.getObjectType(classname.replace(".", "/")).getDescriptor())
          .collect(Collectors.toList());

      return node.visibleAnnotations.stream()
          .anyMatch(a -> annotationTypes.contains(a.desc));
    }
    return false;
  }

  private static List<String> httpMethods() {
    List<String> annotationTypes = httpMethod(GET.class.getPackage().getName(), Path.class);

    // JAXRS annotations
    annotationTypes
        .addAll(httpMethod(javax.ws.rs.GET.class.getPackage().getName(), javax.ws.rs.Path.class));
    return annotationTypes;
  }

  private static List<String> httpMethod(String pkg, Class pathType) {
    List<String> annotationTypes = Router.METHODS.stream()
        .map(m -> pkg + "." + m)
        .collect(Collectors.toList());
    if (pathType != null) {
      annotationTypes.add(Type.getType(pathType).getClassName());
    }
    return annotationTypes;
  }

}
