/*
 * Copyright (c) 2018 - 2019 - Frank Hossfeld
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not
 *  use this file except in compliance with the License. You may obtain a copy of
 *  the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations under
 *  the License.
 */

package com.github.nalukit.nalu.client.internal.route;

import com.github.nalukit.nalu.client.Nalu;
import com.github.nalukit.nalu.client.component.AbstractComponentController;
import com.github.nalukit.nalu.client.component.AbstractCompositeController;
import com.github.nalukit.nalu.client.component.IsShell;
import com.github.nalukit.nalu.client.exception.RoutingInterceptionException;
import com.github.nalukit.nalu.client.filter.IsFilter;
import com.github.nalukit.nalu.client.internal.ClientLogger;
import com.github.nalukit.nalu.client.internal.CompositeControllerReference;
import com.github.nalukit.nalu.client.internal.application.*;
import com.github.nalukit.nalu.client.model.NaluErrorMessage;
import com.github.nalukit.nalu.client.plugin.IsNaluProcessorPlugin;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

abstract class AbstractRouter
    implements ConfiguratableRouter {

  // the plugin
  IsNaluProcessorPlugin plugin;
  // is the application using hash in url?
  boolean               usingHash;
  // route in case of route error
  private String                                            routeError;
  // the latest error object
  private NaluErrorMessage                                  naluErrorMessage;
  // composite configuration
  private List<CompositeControllerReference>                compositeControllerReferences;
  // List of the application shells
  private ShellConfiguration                                shellConfiguration;
  // List of the routes of the application
  private RouterConfiguration                               routerConfiguration;
  // List of active components
  private Map<String, AbstractComponentController<?, ?, ?>> activeComponents;
  // hash of last successful routing
  private String                                            lastExecutedHash = "";
  // last added shell - used, to check if the shell needs an shell replacement
  private String                                            lastAddedShell;
  // instance of the current shell
  private IsShell                                           shell;
  // list of routes used for handling the current route - used to detect loops
  private List<String>                                      loopDetectionList;

  AbstractRouter(List<CompositeControllerReference> compositeControllerReferences,
                 ShellConfiguration shellConfiguration,
                 RouterConfiguration routerConfiguration,
                 IsNaluProcessorPlugin plugin,
                 boolean usingHash) {
    // save the composite configuration reference
    this.compositeControllerReferences = compositeControllerReferences;
    // save the shell configuration reference
    this.shellConfiguration = shellConfiguration;
    // save the router configuration reference
    this.routerConfiguration = routerConfiguration;
    // save te plugin
    this.plugin = plugin;
    // inistantiate lists, etc.
    this.activeComponents = new HashMap<>();
    this.loopDetectionList = new ArrayList<>();
    // save the flag
    this.usingHash = usingHash;
  }

  /**
   * Returns the last error message set by Nalu.
   * <p>
   * Once the error message is consumed, it should be reseted by the developer.
   * (after displayed on the error site!)
   *
   * @return the last set error message or null, if there is none
   */
  public NaluErrorMessage getNaluErrorMessage() {
    return naluErrorMessage;
  }

  /**
   * Clears the Nalu error message.
   * <p>
   * Should be called after the error message is displayed!
   */
  public void clearNaluErrorMessage() {
    this.naluErrorMessage = null;
  }

  /**
   * Sets the error route. (Mostly done by the framework)
   *
   * @param routeError route used by Nalu in case of a routing error
   */
  public void setRouteError(String routeError) {
    this.routeError = routeError;
  }

  /**
   * Stores the instance of the controller in the cache, so that it can be reused the next time
   * the route is called.
   *
   * @param controller controller to store
   * @param <C>        controller type
   */
  public <C extends AbstractComponentController<?, ?, ?>> void storeInCache(C controller) {
    ControllerFactory.get()
                     .storeInCache(controller);
  }

  /**
   * Removes a controller from the chache
   *
   * @param controller controller to be removed
   * @param <C>        controller type
   */
  public <C extends AbstractComponentController<?, ?, ?>> void removeFromCache(C controller) {
    ControllerFactory.get()
                     .removeFromCache(controller);
  }

  /**
   * clears the chache
   */
  public void clearCache() {
    ControllerFactory.get()
                     .clearControllerCache();
  }

  void handleRouting(String hash) {
    // in some cases the hash contains protocoll, port and URI, we clean it
    if (hash.contains("#")) {
      hash = hash.substring(hash.indexOf("#") + 1);
    }
    // logg hash
    RouterLogger.logHandleHash(hash);
    // save have to loo detector list ...
    if (this.loopDetectionList.contains(pimpUpHashForLoopDetection(hash))) {
      // loop discovered .... -> show message
      String message = RouterLogger.logLoopDetected(this.loopDetectionList.get(0));
      // check, if there is a loop containing the error route
      if (this.loopDetectionList.contains(pimpUpHashForLoopDetection(this.routeError))) {
        // YES!! -> just use the alert feature of the plugin
        this.plugin.alert(message);
      } else {
        // NO!! -> route to error site ....
        this.naluErrorMessage = new NaluErrorMessage(Nalu.NALU_ERROR_TYPE_LOOP_DETECTED,
                                                     message);
        this.loopDetectionList.add(pimpUpHashForLoopDetection(this.routeError));
        this.route(this.routeError);
      }
      // abort handling!
      return;
    } else {
      this.loopDetectionList.add(pimpUpHashForLoopDetection(hash));
    }
    // parse hash ...
    RouteResult routeResult;
    try {
      routeResult = this.parse(hash);
    } catch (RouterException e) {
      this.naluErrorMessage = new NaluErrorMessage(Nalu.NALU_ERROR_TYPE_NO_CONTROLLER_INSTANCE,
                                                   RouterLogger.logNoMatchingRoute(hash,
                                                                                   this.routeError));
      if (!Objects.isNull(this.routeError)) {
        // loop discovered .... -> show message
        String message = RouterLogger.logLoopDetected(this.loopDetectionList.get(0));
        // check, if there is a loop containing the error route
        if (this.loopDetectionList.contains(pimpUpHashForLoopDetection(this.routeError))) {
          // YES!! -> just use the alert feature of the plugin
          this.plugin.alert(message);
          return;
        }
        RouterLogger.logUseErrorRoute(this.routeError);
        this.loopDetectionList.add(pimpUpHashForLoopDetection(hash));
        this.route(this.routeError,
                   true);
      } else {
        // should never be seen!
        this.plugin.alert("No error Route defeined");
      }
      return;
    }
    // First we have to check if there is a filter
    // if there are filters ==>  filter the route
    for (IsFilter filter : this.routerConfiguration.getFilters()) {
      if (!filter.filter(addLeadindgSlash(routeResult.getRoute()),
                         routeResult.getParameterValues()
                                    .toArray(new String[0]))) {
        RouterLogger.logFilterInterceptsRouting(filter.getClass()
                                                      .getCanonicalName(),
                                                filter.redirectTo(),
                                                filter.parameters());
        this.route(filter.redirectTo(),
                   true,
                   filter.parameters());
        return;
      }
    }
    // search for a matching routing
    List<RouteConfig> routeConfigurations = this.routerConfiguration.match(routeResult.getRoute());
    // check whether or not the routing is possible ...
    if (this.confirmRouting(routeConfigurations)) {
      // call stop for all elements
      this.stopController(routeConfigurations);
      // handle shellCreator
      //
      // in case shellCreator changed or is not set, use the actual shellCreator!
      if (this.lastAddedShell == null ||
          !routeResult.getShell()
                      .equals(this.lastAddedShell)) {
        // add shellCreator to the viewport
        ShellConfig shellConfig = this.shellConfiguration.match(routeResult.getShell());
        if (!Objects.isNull(shellConfig)) {
          ShellInstance shellInstance = ShellFactory.get()
                                                    .shell(shellConfig.getClassName());
          if (!Objects.isNull(shellInstance)) {
            // in case there is an instance of an shellCreator existing, call the onDetach mehtod inside the shellCreator
            if (!Objects.isNull(this.shell)) {
              ClientLogger.get()
                          .logDetailed("Router: detach shellCreator >>" +
                                       this.shell.getClass()
                                                 .getCanonicalName() +
                                       "<<",
                                       1);
              this.shell.detachShell();
              ClientLogger.get()
                          .logDetailed("Router: shellCreator >>" +
                                       this.shell.getClass()
                                                 .getCanonicalName() +
                                       "<< detached",
                                       1);
            }
            // set newe shellCreator value
            this.shell = shellInstance.getShell();
            // save the last added shellCreator ....
            this.lastAddedShell = routeResult.getShell();
            // initialize shellCreator ...
            ClientLogger.get()
                        .logDetailed("Router: attach shellCreator >>" + routeResult.getShell() + "<<",
                                     1);
            shellInstance.getShell()
                         .attachShell();
            ClientLogger.get()
                        .logDetailed("Router: shellCreator >>" + routeResult.getShell() + "<< attached",
                                     1);
            // start the application by calling url + '#'
            ClientLogger.get()
                        .logDetailed("Router: initialize shellCreator >>" + routeResult.getShell() + "<< (route to '/')",
                                     1);
            // get shellCreator matching root configs ...
            List<RouteConfig> shellMatchingRouteConfigurations = this.routerConfiguration.match(routeResult.getShell());
            for (RouteConfig routeConfiguraion : shellMatchingRouteConfigurations) {
              this.handleRouteConfig(routeConfiguraion,
                                     routeResult,
                                     hash);
            }
          }
        } else {
          RouterLogger.logUseErrorRoute(this.routeError);
          this.route(this.routeError,
                     true);
        }
      }
      // routing
      for (RouteConfig routeConfiguraion : routeConfigurations) {
        this.handleRouteConfig(routeConfiguraion,
                               routeResult,
                               hash);
      }
      this.shell.onAttachedComponent();
      RouterLogger.logShellOnAttachedComponentMethodCalled(this.shell.getClass()
                                                                     .getCanonicalName());

    } else {
      this.plugin.route("#" + this.lastExecutedHash,
                        false,
                        this.usingHash);
    }
  }

  private void handleRouteConfig(RouteConfig routeConfiguraion,
                                 RouteResult routeResult,
                                 String hash) {
    ControllerInstance controller;
    try {
      controller = ControllerFactory.get()
                                    .controller(routeConfiguraion.getClassName(),
                                                routeResult.getParameterValues()
                                                           .toArray(new String[0]));
    } catch (RoutingInterceptionException e) {
      RouterLogger.logControllerInterceptsRouting(e.getControllerClassName(),
                                                  e.getRoute(),
                                                  e.getParameter());
      this.route(e.getRoute(),
                 true,
                 e.getParameter());
      return;
    }
    // do the routing ...
    doRouting(hash,
              routeResult,
              routeConfiguraion,
              controller);
  }

  private void doRouting(String hash,
                         RouteResult hashResult,
                         RouteConfig routeConfiguraion,
                         ControllerInstance controllerInstance) {
    if (Objects.isNull(controllerInstance.getController())) {
      this.naluErrorMessage = new NaluErrorMessage(Nalu.NALU_ERROR_TYPE_NO_CONTROLLER_INSTANCE,
                                                   RouterLogger.logNoControllerFoundForHash(hash));
      if (!Objects.isNull(this.routeError)) {
        RouterLogger.logUseErrorRoute(this.routeError);
        this.route(this.routeError,
                   true);
      } else {
        // should never be seen!
        this.plugin.alert("Ups ... not found!");
      }
    } else {
      // handle controller
      controllerInstance.getController()
                        .setRouter(this);
      // handle composite of the controller
      RouterLogger.logControllerLookForCompositeCotroller(controllerInstance.getController()
                                                                            .getClass()
                                                                            .getCanonicalName());
      List<CompositeControllerReference> compositeForController = this.getCompositeForController(controllerInstance.getController()
                                                                                                                   .getClass()
                                                                                                                   .getCanonicalName());
      List<AbstractCompositeController<?, ?, ?>> compositeControllers = new ArrayList<>();
      if (compositeForController.size() > 0) {
        RouterLogger.logControllerCompositeControllerFound(controllerInstance.getController()
                                                                             .getClass()
                                                                             .getCanonicalName(),
                                                           compositeForController.size());
        compositeForController.forEach(s -> {
          try {
            CompositeInstance compositeInstance = CompositeFactory.get()
                                                                  .getComposite(s.getComposite(),
                                                                                hashResult.getParameterValues()
                                                                                          .toArray(new String[0]));
            if (compositeInstance == null) {
              RouterLogger.logCompositeNotFound(controllerInstance.getController()
                                                                  .getClass()
                                                                  .getCanonicalName(),
                                                s.getCompositeName());

            } else {
              compositeControllers.add(compositeInstance.getComposite());
              // inject router into composite
              compositeInstance.getComposite()
                               .setRouter(this);
              // inject composite into controller
              controllerInstance.getController()
                                .getComposites()
                                .put(s.getCompositeName(),
                                     compositeInstance.getComposite());
              RouterLogger.logCompositeControllerInjectedInController(compositeInstance.getComposite()
                                                                                       .getClass()
                                                                                       .getCanonicalName(),
                                                                      controllerInstance.getController()
                                                                                        .getClass()
                                                                                        .getCanonicalName());
            }
          } catch (RoutingInterceptionException e) {
            RouterLogger.logControllerInterceptsRouting(e.getControllerClassName(),
                                                        e.getRoute(),
                                                        e.getParameter());
            this.route(e.getRoute(),
                       true,
                       e.getParameter());
          }
        });
      } else {
        RouterLogger.logControllerNoCompositeControllerFound(controllerInstance.getController()
                                                                               .getClass()
                                                                               .getCanonicalName());
      }
      this.append(routeConfiguraion.getSelector(),
                  controllerInstance.getController());
      // append composite
      for (AbstractCompositeController<?, ?, ?> compositeController : compositeControllers) {
        CompositeControllerReference reference = null;
        for (CompositeControllerReference sfc : compositeForController) {
          if (compositeController.getClass()
                                 .getCanonicalName()
                                 .equals(sfc.getComposite())) {
            reference = sfc;
            break;
          }
        }
        if (reference != null) {
          this.append(reference.getSelector(),
                      compositeController);
          RouterLogger.logControllerOnAttachedCompositeController(controllerInstance.getController()
                                                                                    .getClass()
                                                                                    .getCanonicalName(),
                                                                  compositeController.getClass()
                                                                                     .getCanonicalName());
        }
      }
      controllerInstance.getController()
                        .onAttach();
      RouterLogger.logControllerOnAttachedMethodCalled(controllerInstance.getController()
                                                                         .getClass()
                                                                         .getCanonicalName());
      // call start method only in case the controller was not stored!
      if (!controllerInstance.isChached()) {
        compositeControllers.forEach(s -> {
          s.start();
          RouterLogger.logCompositeComntrollerStartMethodCalled(s.getClass()
                                                                 .getCanonicalName());
        });
        controllerInstance.getController()
                          .start();
        RouterLogger.logControllerStartMethodCalled(controllerInstance.getController()
                                                                      .getClass()
                                                                      .getCanonicalName());
      }
      this.lastExecutedHash = hash;
      // clear loo detection list ...
      this.loopDetectionList.clear();
    }

  }

  /**
   * Parse the hash and divides it into shellCreator, route and parameters
   *
   * @param route ths hash to parse
   * @return parse result
   * @throws com.github.nalukit.nalu.client.internal.route.RouterException in case no controller is found for the routing
   */
  public RouteResult parse(String route)
      throws RouterException {
    RouteResult hashResult = new RouteResult();
    String hashValue = route;
    // only the part after the first # is intresting:
    if (hashValue.contains("#")) {
      hashValue = hashValue.substring(hashValue.indexOf("#") + 1);
    }
    // extract shellCreator first:
    if (hashValue.startsWith("/")) {
      hashValue = hashValue.substring(1);
    }
    // check, if there are more "/"
    if (hashValue.contains("/")) {
      hashResult.setShell("/" +
                          hashValue.substring(0,
                                              hashValue.indexOf("/")));
    } else {
      hashResult.setShell("/" + hashValue);
    }
    // check, if the shellCreator exists ....
    Optional<String> optional = this.shellConfiguration.getShells()
                                                       .stream()
                                                       .map(ShellConfig::getRoute)
                                                       .filter(f -> f.equals(hashResult.getShell()))
                                                       .findAny();
    if (!optional.isPresent()) {
      StringBuilder sb = new StringBuilder();
      sb.append("no matching shellCreator found for hash >>")
        .append(route)
        .append("<< --> Routing aborted!");
      RouterLogger.logSimple(sb.toString(),
                             1);
      throw new RouterException(sb.toString());
    }
    // extract route first:
    hashValue = route;
    if (hashValue.startsWith("/")) {
      hashValue = hashValue.substring(1);
    }
    if (hashValue.contains("/")) {
      String searchPart = hashValue;
      do {
        String finalSearchPart = "/" + searchPart;
        if (this.routerConfiguration.getRouters()
                                    .stream()
                                    .anyMatch(f -> f.getRoute()
                                                    .equals(finalSearchPart))) {
          hashResult.setRoute("/" + searchPart);
          break;
        } else {
          if (searchPart.contains("/")) {
            searchPart = searchPart.substring(0,
                                              searchPart.lastIndexOf("/"));
          } else {
            break;
          }
        }
      } while (searchPart.length() > 1);
      if (hashResult.getRoute() == null) {
        StringBuilder sb = new StringBuilder();
        sb.append("no matching route found for hash >>")
          .append(route)
          .append("<< --> Routing aborted!");
        RouterLogger.logSimple(sb.toString(),
                               1);
        throw new RouterException(sb.toString());
      } else {
        if (!hashResult.getRoute()
                       .equals("/" + hashValue)) {
          String parametersFromHash = hashValue.substring(hashResult.getRoute()
                                                                    .length());
          // lets get the parameters!
          List<String> paramsList = Stream.of(parametersFromHash.split("/"))
                                          .collect(Collectors.toList());
          // reset slash in params ....
          paramsList.forEach(parm -> hashResult.getParameterValues()
                                               .add(parm.replace(Nalu.NALU_SLASH_REPLACEMENT,
                                                                 "/")));
          // check the number of parameter
          // if the hash contains more paremters then the configuration
          // accepts, throw an exception!
          for (RouteConfig routeConfig : this.routerConfiguration.getRouters()) {
            if (routeConfig.getRoute()
                           .equals(hashResult.getRoute())) {
              if (routeConfig.getParameters()
                             .size() <
                  hashResult.getParameterValues()
                            .size()) {
                throw new RouterException(RouterLogger.logWrongNumbersOfPrameters(route,
                                                                                  hashResult.getRoute(),
                                                                                  routeConfig.getParameters()
                                                                                             .size(),
                                                                                  hashResult.getParameterValues()
                                                                                            .size()));

              }
            }
          }
        }
      }
    } else {
      String finalSearchPart = "/" + hashValue;
      if (this.routerConfiguration.getRouters()
                                  .stream()
                                  .anyMatch(f -> f.match(finalSearchPart))) {
        hashResult.setRoute("/" + hashValue);
      } else {
        throw new RouterException(RouterLogger.logNoMatchingRoute(route));
      }
    }
    return hashResult;
  }

  private String addLeadindgSlash(String value) {
    if (value.startsWith("/")) {
      return value;
    }
    return "/" + value;
  }

  private boolean confirmRouting(List<RouteConfig> routeConfigurations) {
    AtomicBoolean isDirtyComposite = new AtomicBoolean(false);
    routeConfigurations.stream()
                       .map(config -> this.activeComponents.get(config.getSelector()))
                       .filter(Objects::nonNull)
                       .forEach(c -> {
                         Optional<String> optional = c.getComposites()
                                                      .values()
                                                      .stream()
                                                      .map(AbstractCompositeController::mayStop)
                                                      .filter(Objects::nonNull)
                                                      .findFirst();
                         if (optional.isPresent()) {
                           this.plugin.confirm(optional.get());
                           isDirtyComposite.set(true);
                         }
                       });

    return !isDirtyComposite.get() &&
           routeConfigurations.stream()
                              .map(config -> this.activeComponents.get(config.getSelector()))
                              .filter(Objects::nonNull)
                              .map(AbstractComponentController::mayStop)
                              .filter(Objects::nonNull)
                              .allMatch(message -> this.plugin.confirm(message));
  }

  private void stopController(List<RouteConfig> routeConfiguraions) {
    routeConfiguraions.stream()
                      .map(config -> this.activeComponents.get(config.getSelector()))
                      .filter(Objects::nonNull)
                      .forEach(controller -> {
                        // stop controller
                        RouterLogger.logControllerHandlingStop(controller.getClass()
                                                                         .getCanonicalName());
                        RouterLogger.logControllerHandlingStopComposites(controller.getClass()
                                                                                   .getCanonicalName());
                        // stop compositeComntrollers
                        controller.getComposites()
                                  .values()
                                  .forEach(s -> {
                                    RouterLogger.logCompositeControllerStopMethodWillBeCalled(s.getClass()
                                                                                               .getCanonicalName());
                                    s.getClass()
                                     .getCanonicalName();
                                    s.stop();
                                    RouterLogger.logCompositeControllerRemoveMethodCalled(s.getClass()
                                                                                           .getCanonicalName());
                                    s.remove();
                                    RouterLogger.logCompositeControllerStopMethodCalled(s.getClass()
                                                                                         .getCanonicalName());
                                    s.onDetach();
                                    RouterLogger.logCompositeControllerDetached(s.getClass()
                                                                                 .getCanonicalName());
                                    s.removeHandlers();
                                    RouterLogger.logCompositeControllerRemoveHandlersMethodCalled(s.getClass()
                                                                                                   .getCanonicalName());
                                    s.getComponent()
                                     .onDetach();
                                    RouterLogger.logCompositeComponentDetached(s.getComponent()
                                                                                .getClass()
                                                                                .getCanonicalName());
                                    s.getComponent()
                                     .removeHandlers();
                                    RouterLogger.logCompositeComponentRemoveHandlersMethodCalled(s.getComponent()
                                                                                                  .getClass()
                                                                                                  .getCanonicalName());
                                    RouterLogger.logCompositeControllerStopped(controller.getClass()
                                                                                         .getCanonicalName());
                                  });

                        RouterLogger.logControllerCompositesStoppped(controller.getClass()
                                                                               .getCanonicalName());

                        // stop controller
                        RouterLogger.logControllerStopMethodWillBeCalled(controller.getClass()
                                                                                   .getCanonicalName());
                        controller.stop();
                        RouterLogger.logControllerStopMethodCalled(controller.getClass()
                                                                             .getCanonicalName());
                        controller.onDetach();
                        RouterLogger.logControllerDetached(controller.getClass()
                                                                     .getCanonicalName());
                        controller.removeHandlers();
                        RouterLogger.logControllerRemoveHandlersMethodCalled(controller.getClass()
                                                                                       .getCanonicalName());
                        controller.getComponent()
                                  .onDetach();
                        RouterLogger.logComponentDetached(controller.getComponent()
                                                                    .getClass()
                                                                    .getCanonicalName());
                        controller.getComponent()
                                  .removeHandlers();
                        RouterLogger.logComponentRemoveHandlersMethodCalled(controller.getComponent()
                                                                                      .getClass()
                                                                                      .getCanonicalName());
                        RouterLogger.logControllerStopped(controller.getClass()
                                                                    .getCanonicalName());
                      });
    routeConfiguraions.forEach(routeConfiguraion -> this.plugin.remove(routeConfiguraion.getSelector()));
    routeConfiguraions.stream()
                      .map(config -> this.activeComponents.get(config.getSelector()))
                      .filter(Objects::nonNull)
                      .forEach(c -> this.activeComponents.remove(c));
  }

  private void append(String selector,
                      AbstractComponentController<?, ?, ?> controller) {
    if (this.plugin.attach(selector,
                           controller.asElement())) {
      // save to active components
      this.activeComponents.put(selector,
                                controller);
    }
  }

  private void append(String selector,
                      AbstractCompositeController<?, ?, ?> compositeController) {
    if (!this.plugin.attach(selector,
                            compositeController.asElement())) {
      String sb = "no element found, that matches selector >>" + selector + "<< --> Routing aborted!";
      RouterLogger.logSimple(sb,
                             1);
      this.naluErrorMessage = new NaluErrorMessage(Nalu.NALU_ERROR_TYPE_NO_SELECTOR_FOUND,
                                                   sb);
      RouterLogger.logUseErrorRoute(this.routeError);
      this.route(this.routeError,
                 true);
    }
  }

  private List<CompositeControllerReference> getCompositeForController(String controllerClassName) {
    return this.compositeControllerReferences.stream()
                                             .filter(s -> controllerClassName.equals(s.getController()))
                                             .collect(Collectors.toList());
  }

  /**
   * The method routes to another screen. In case it is called,
   * it will:
   * <ul>
   * <li>create a new hash</li>
   * <li>update the url</li>
   * </ul>
   * Once the url gets updated, it triggers the onahshchange event and Nalu starts to work
   *
   * @param newRoute routing goal
   * @param parms    list of parameters [0 - n]
   */
  public void route(String newRoute,
                    String... parms) {
    this.route(newRoute,
               false,
               parms);
  }

  private void route(String newRoute,
                     boolean replaceState,
                     String... parms) {
    String newRouteWithParams = this.generate(newRoute,
                                              parms);
    if (replaceState) {
      this.plugin.route(newRouteWithParams,
                        true,
                        this.usingHash);
    } else {
      this.plugin.route(newRouteWithParams,
                        false,
                        this.usingHash);
    }
    this.handleRouting(newRouteWithParams);
  }

  public String generate(String route,
                         String... parms) {
    StringBuilder sb = new StringBuilder();
    if (route.startsWith("/")) {
      route = route.substring(1);
    }
    sb.append(route);
    if (parms != null) {
      Stream.of(parms)
            .filter(Objects::nonNull)
            .forEach(s -> sb.append("/")
                            .append(s.replace("/",
                                              Nalu.NALU_SLASH_REPLACEMENT)));
    }
    return sb.toString();
  }

  private String pimpUpHashForLoopDetection(String hash) {
    String value = hash;
    if (value.startsWith("#")) {
      value = value.substring(1);
    }
    if (value.startsWith("/")) {
      value = value.substring(1);
    }
    return value;
  }

  /**
   * Returns a map of query parameters that was available at application start.
   *
   * @return list of query parameters at application start
   */
  public Map<String, String> getStartQueryParameters() {
    return this.plugin.getQueryParameters();
  }

}