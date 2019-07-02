/**
 * Jooby https://jooby.io
 * Apache License Version 2.0 https://jooby.io/LICENSE.txt
 * Copyright 2014 Edgar Espina
 */
package io.jooby;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Route contains information about the HTTP method, path pattern, which content types consumes and
 * produces, etc..
 *
 * Additionally, contains metadata about route return Java type, argument source (query, path, etc..) and
 * Java type.
 *
 * This class contains all the metadata associated to a route. It is like a {@link Class} object
 * for routes.
 *
 * @author edgar
 * @since 2.0.0
 */
public class Route {

  /**
   * Decorates a route handler by running logic before and after route handler. This pattern is
   * also known as Filter.
   *
   * <pre>{@code
   * {
   *   decorator(next -> ctx -> {
   *     long start = System.currentTimeMillis();
   *     Object result = next.apply(ctx);
   *     long end = System.currentTimeMillis();
   *     System.out.println("Took: " + (end - start));
   *     return result;
   *   });
   * }
   * }</pre>
   *
   * @author edgar
   * @since 2.0.0
   */
  public interface Decorator extends Serializable {
    /**
     * Chain the decorator within next handler.
     *
     * @param next Next handler.
     * @return A new handler.
     */
    @Nonnull Handler apply(@Nonnull Handler next);

    /**
     * Chain this decorator with another and produces a new decorator.
     *
     * @param next Next decorator.
     * @return A new decorator.
     */
    @Nonnull default Decorator then(@Nonnull Decorator next) {
      return h -> apply(next.apply(h));
    }

    /**
     * Chain this decorator with a handler and produces a new handler.
     *
     * @param next Next handler.
     * @return A new handler.
     */
    @Nonnull default Handler then(@Nonnull Handler next) {
      return ctx -> apply(next).apply(ctx);
    }
  }

  /**
   * Decorates a handler and run logic before handler is executed.
   *
   * @author edgar
   * @since 2.0.0
   */
  public interface Before extends Serializable {
    /**
     * Execute application code before next handler.
     *
     * @param ctx Web context.
     * @throws Exception If something goes wrong.
     */
    void apply(@Nonnull Context ctx) throws Exception;

    /**
     * Chain this filter with next one and produces a new before filter.
     *
     * @param next Next decorator.
     * @return A new decorator.
     */
    @Nonnull default Before then(@Nonnull Before next) {
      return ctx -> {
        apply(ctx);
        next.apply(ctx);
      };
    }

    /**
     * Chain this decorator with a handler and produces a new handler.
     *
     * @param next Next handler.
     * @return A new handler.
     */
    @Nonnull default Handler then(@Nonnull Handler next) {
      return ctx -> {
        apply(ctx);
        return next.apply(ctx);
      };
    }
  }

  /**
   * Execute application logic after a response has been generated by a route handler.
   *
   * @author edgar
   * @since 2.0.0
   */
  public interface After extends Serializable {

    /**
     * Chain this decorator with next one and produces a new after decorator.
     *
     * @param next Next decorator.
     * @return A new decorator.
     */
    @Nonnull default After then(@Nonnull After next) {
      return (ctx, result) -> {
        next.apply(ctx, result);
        apply(ctx, result);
      };
    }

    /**
     * Execute application logic on a route response.
     *
     * @param ctx Web context.
     * @param result Response generated by route handler.
     * @throws Exception If something goes wrong.
     */
    @Nonnull void apply(@Nonnull Context ctx, Object result) throws Exception;
  }

  /**
   * Route handler here is where the application logic lives.
   *
   * @author edgar
   * @since 2.0.0
   */
  public interface Handler extends Serializable {

    /**
     * Allows a handler to listen for route metadata.
     *
     * @param route Route metadata.
     * @return This handler.
     */
    default @Nonnull Handler setRoute(@Nonnull Route route) {
      // NOOP
      return this;
    }

    /**
     * Execute application code.
     *
     * @param ctx Web context.
     * @return Route response.
     * @throws Exception If something goes wrong.
     */
    @Nonnull Object apply(@Nonnull Context ctx) throws Exception;

    /**
     * Chain this after decorator with next and produces a new decorator.
     *
     * @param next Next decorator.
     * @return A new handler.
     */
    @Nonnull default Handler then(@Nonnull After next) {
      return ctx -> {
        Object result = apply(ctx);
        next.apply(ctx, result);
        return result;
      };
    }
  }

  /**
   * Handler for {@link StatusCode#NOT_FOUND} responses.
   */
  public static final Handler NOT_FOUND = ctx -> ctx
      .sendError(new StatusCodeException(StatusCode.NOT_FOUND));

  /**
   * Handler for {@link StatusCode#METHOD_NOT_ALLOWED} responses.
   */
  public static final Handler METHOD_NOT_ALLOWED = ctx -> ctx
      .sendError(new StatusCodeException(StatusCode.METHOD_NOT_ALLOWED));

  /** Handler for {@link StatusCode#NOT_ACCEPTABLE} responses. */
  public static final Route.Before ACCEPT = ctx -> {
    List<MediaType> produceTypes = ctx.getRoute().getProduces();
    MediaType contentType = ctx.accept(produceTypes);
    if (contentType == null) {
      throw new StatusCodeException(StatusCode.NOT_ACCEPTABLE,
          ctx.header(Context.ACCEPT).value(""));
    }
  };

  /** Handler for {@link StatusCode#UNSUPPORTED_MEDIA_TYPE} responses. */
  public static final Route.Before SUPPORT_MEDIA_TYPE = ctx -> {
    MediaType contentType = ctx.getRequestType();
    if (contentType == null) {
      throw new StatusCodeException(StatusCode.UNSUPPORTED_MEDIA_TYPE);
    }
    if (!ctx.getRoute().getConsumes().stream().anyMatch(contentType::matches)) {
      throw new StatusCodeException(StatusCode.UNSUPPORTED_MEDIA_TYPE, contentType.getValue());
    }
  };

  /**
   * Favicon handler as a silent 404 error.
   */
  public static final Handler FAVICON = ctx -> ctx.send(StatusCode.NOT_FOUND);

  private static final List EMPTY_LIST = Collections.emptyList();

  private static final Map EMPTY_MAP = Collections.emptyMap();

  private Map<String, Parser> parsers = EMPTY_MAP;

  private final String pattern;

  private final String method;

  private List<String> pathKeys = EMPTY_LIST;

  private Before before;

  private Decorator decorator;

  private Handler handler;

  private After after;

  private Handler pipeline;

  private Renderer renderer;

  private Type returnType;

  private Object handle;

  private List<MediaType> produces = EMPTY_LIST;

  private List<MediaType> consumes = EMPTY_LIST;

  /**
   * Creates a new route.
   *
   * @param method HTTP method.
   * @param pattern Path pattern.
   * @param handler Route handler.
   */
  public Route(@Nonnull String method, @Nonnull String pattern, @Nonnull Handler handler) {
    this.method = method.toUpperCase();
    this.pattern = pattern;
    this.handler = handler;
    this.handle = handler;
  }

  /**
   * Path pattern.
   *
   * @return Path pattern.
   */
  public @Nonnull String getPattern() {
    return pattern;
  }

  /**
   * HTTP method.
   *
   * @return HTTP method.
   */
  public @Nonnull String getMethod() {
    return method;
  }

  /**
   * Path keys.
   *
   * @return Path keys.
   */
  public @Nonnull List<String> getPathKeys() {
    return pathKeys;
  }

  /**
   * Set path keys.
   *
   * @param pathKeys Path keys or empty list.
   * @return This route.
   */
  public @Nonnull Route setPathKeys(@Nonnull List<String> pathKeys) {
    this.pathKeys = pathKeys;
    return this;
  }

  /**
   * Route handler.
   *
   * @return Route handler.
   */
  public @Nonnull Handler getHandler() {
    return handler;
  }

  /**
   * Route pipeline.
   *
   * @return Route pipeline.
   */
  public @Nonnull Handler getPipeline() {
    if (pipeline == null) {
      pipeline = computePipeline();
    }
    return pipeline;
  }

  /**
   * Handler instance which might or might not be the same as {@link #getHandler()}.
   *
   * The handle is required to extract correct metadata.
   *
   * @return Handle.
   */
  public @Nonnull Object getHandle() {
    return handle;
  }

  /**
   * Before pipeline or <code>null</code>.
   *
   * @return Before pipeline or <code>null</code>.
   */
  public @Nullable Before getBefore() {
    return before;
  }

  /**
   * Set before filter.
   *
   * @param before Before filter.
   * @return This route.
   */
  public @Nonnull Route setBefore(@Nullable Before before) {
    this.before = before;
    return this;
  }

  /**
   * After filter or <code>null</code>.
   *
   * @return After filter or <code>null</code>.
   */
  public @Nullable After getAfter() {
    return after;
  }

  /**
   * Set after filter.
   *
   * @param after After filter.
   * @return This route.
   */
  public @Nonnull Route setAfter(@Nonnull After after) {
    this.after = after;
    return this;
  }

  /**
   * Decorator or <code>null</code>.
   *
   * @return Decorator or <code>null</code>.
   */
  public @Nullable Decorator getDecorator() {
    return decorator;
  }

  /**
   * Set route decorator.
   *
   * @param decorator Decorator.
   * @return This route.
   */
  public @Nonnull Route setDecorator(@Nullable Decorator decorator) {
    this.decorator = decorator;
    return this;
  }

  /**
   * Set route handle instance, required when handle is different from {@link #getHandler()}.
   *
   * @param handle Handle instance.
   * @return This route.
   */
  public @Nonnull Route setHandle(@Nonnull Object handle) {
    this.handle = handle;
    return this;
  }

  /**
   * Set route pipeline. This method is part of public API but isn't intended to be used by public.
   *
   * @param pipeline Pipeline.
   * @return This routes.
   */
  public @Nonnull Route setPipeline(Route.Handler pipeline) {
    this.pipeline = pipeline;
    return this;
  }

  /**
   * Route renderer.
   *
   * @return Route renderer.
   */
  public @Nonnull Renderer getRenderer() {
    return renderer;
  }

  /**
   * Set renderer.
   *
   * @param renderer Renderer.
   * @return This route.
   */
  public @Nonnull Route setRenderer(@Nonnull Renderer renderer) {
    this.renderer = renderer;
    return this;
  }

  /**
   * Return return type.
   *
   * @return Return type.
   */
  public @Nullable Type getReturnType() {
    return returnType;
  }

  /**
   * Set route return type.
   *
   * @param returnType Return type.
   * @return This route.
   */
  public @Nonnull Route setReturnType(@Nullable Type returnType) {
    this.returnType = returnType;
    return this;
  }

  /**
   * Response types (format) produces by this route. If set, we expect to find a match in the
   * <code>Accept</code> header. If none matches, we send a {@link StatusCode#NOT_ACCEPTABLE}
   * response.
   *
   * @return Immutable list of produce types.
   */
  public @Nonnull List<MediaType> getProduces() {
    return produces;
  }

  /**
   * Add one or more response types (format) produces by this route.
   *
   * @param produces Produce types.
   * @return This route.
   */
  public @Nonnull Route produces(@Nonnull MediaType... produces) {
    return setProduces(Arrays.asList(produces));
  }

  /**
   * Add one or more response types (format) produces by this route.
   *
   * @param produces Produce types.
   * @return This route.
   */
  public @Nonnull Route setProduces(@Nonnull Collection<MediaType> produces) {
    if (produces.size() > 0) {
      if (this.produces == EMPTY_LIST) {
        this.produces = new ArrayList<>();
      }
      produces.forEach(this.produces::add);
      before = before == null ? ACCEPT : ACCEPT.then(before);
    }
    return this;
  }

  /**
   * Request types (format) consumed by this route. If set the <code>Content-Type</code> header
   * is checked against these values. If none matches we send a
   * {@link StatusCode#UNSUPPORTED_MEDIA_TYPE} exception.
   *
   * @return Immutable list of consumed types.
   */
  public @Nonnull List<MediaType> getConsumes() {
    return consumes;
  }

  /**
   * Add one or more request types (format) consumed by this route.
   *
   * @param consumes Consume types.
   * @return This route.
   */
  public @Nonnull Route consumes(@Nonnull MediaType... consumes) {
    return setConsumes(Arrays.asList(consumes));
  }

  /**
   * Add one or more request types (format) consumed by this route.
   *
   * @param consumes Consume types.
   * @return This route.
   */
  public @Nonnull Route setConsumes(@Nonnull Collection<MediaType> consumes) {
    if (consumes.size() > 0) {
      if (this.consumes == EMPTY_LIST) {
        this.consumes = new ArrayList<>();
      }
      consumes.forEach(this.consumes::add);
      before = before == null ? SUPPORT_MEDIA_TYPE : SUPPORT_MEDIA_TYPE.then(before);
    }
    return this;
  }

  /**
   * Parser for given media type.
   *
   * @param contentType Media type.
   * @return Parser.
   */
  public @Nonnull Parser parser(@Nonnull MediaType contentType) {
    return parsers.getOrDefault(contentType.getValue(), Parser.UNSUPPORTED_MEDIA_TYPE);
  }

  /**
   * Route message decoder.
   *
   * @return Message decoders.
   */
  public @Nonnull Map<String, Parser> getParsers() {
    return parsers;
  }

  /**
   * Set message decoders. Map key is a mime-type.
   *
   * @param decoders message decoder.
   * @return This route.
   */
  public @Nonnull Route setParsers(@Nonnull Map<String, Parser> decoders) {
    this.parsers = decoders;
    return this;
  }

  @Override public String toString() {
    return method + " " + pattern;
  }

  private Route.Handler computePipeline() {
    Route.Handler pipeline = decorator == null ? handler : decorator.then(handler);

    if (before != null) {
      pipeline = before.then(pipeline);
    }

    if (after != null) {
      pipeline = pipeline.then(after);
    }
    return pipeline;
  }
}
