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

package com.github.nalukit.nalu.plugin.elemento.client;

import com.github.nalukit.nalu.client.internal.PropertyFactory;
import com.github.nalukit.nalu.client.internal.route.ShellConfiguration;
import com.github.nalukit.nalu.client.plugin.IsNaluProcessorPlugin;
import com.github.nalukit.nalu.plugin.core.web.client.NaluPluginCoreWeb;
import com.github.nalukit.nalu.plugin.core.web.client.model.NaluStartModel;
import elemental2.core.Global;
import elemental2.dom.DomGlobal;
import elemental2.dom.Element;
import elemental2.dom.HTMLElement;
import elemental2.dom.HTMLMetaElement;
import elemental2.dom.NodeList;
import org.jboss.gwt.elemento.core.IsElement;

import java.util.Map;
import java.util.Objects;

public class NaluPluginElemento
    implements IsNaluProcessorPlugin {

  private NaluStartModel naluStartModel;

  /* RouteChangeHandler - to be used directly   */
  /* in case Nalu does not have history support */
  private RouteChangeHandler routeChangeHandler;

  public NaluPluginElemento() {
    super();
  }

  @Override
  public void alert(String message) {
    DomGlobal.window.alert(message);
  }

  @Override
  public boolean attach(String selector,
                        Object content) {
    Element selectorElement = DomGlobal.document.querySelector("#" + selector);
    if (selectorElement == null) {
      return false;
    } else {
      if (content instanceof Iterable) {
        Iterable<?> elements = (Iterable<?>) content;
        for (Object element : elements) {
          if (element instanceof IsElement) {
            selectorElement.appendChild(((IsElement) element).element());
          } else if (element instanceof HTMLElement) {
            selectorElement.appendChild(((HTMLElement) element));
          }
        }
      } else if (content instanceof IsElement) {
        selectorElement.appendChild(((IsElement) content).element());
      } else if (content instanceof HTMLElement) {
        selectorElement.appendChild(((HTMLElement) content));
      }
      return true;
    }
  }

  @Override
  public boolean confirm(String message) {
    return DomGlobal.window.confirm(message);
  }

  @Override
  public String getStartRoute() {
    return this.naluStartModel.getStartRoute();
  }

  @Override
  public Map<String, String> getQueryParameters() {
    return this.naluStartModel.getQueryParameters();
  }

  @Override
  public void register(RouteChangeHandler handler) {
    if (PropertyFactory.get()
                       .hasHistory()) {
      if (PropertyFactory.get()
                         .isUsingHash()) {
        NaluPluginCoreWeb.addOnHashChangeHandler(handler);
      } else {
        NaluPluginCoreWeb.addPopStateHandler(handler,
                                             PropertyFactory.get()
                                                            .getContextPath());
      }
    } else {
      this.routeChangeHandler = handler;
    }
  }

  @Override
  public void remove(String selector) {
    Element selectorElement = DomGlobal.document.querySelector("#" + selector);
    if (selectorElement != null) {
      if (selectorElement.childNodes.length > 0) {
        for (int i = selectorElement.childNodes.asList()
                                               .size() - 1; i > -1; i--) {
          selectorElement.removeChild(selectorElement.childNodes.asList()
                                                                .get(i));
        }
      }
    }
  }

  @Override
  public void route(String newRoute,
                    boolean replace) {
    NaluPluginCoreWeb.route(newRoute,
                            replace,
                            this.routeChangeHandler);
  }

  @Override
  public void initialize(ShellConfiguration shellConfiguration) {
    // Sets the context path inside the PropertyFactory
    NaluPluginCoreWeb.getContextPath(shellConfiguration);
    this.naluStartModel = NaluPluginCoreWeb.getNaluStartModel();
  }

  @Override
  public void updateTitle(String title) {
    DomGlobal.document.title = title;
  }

  @Override
  public void updateMetaNameContent(String name,
                                    String content) {
    NodeList<Element> metaTagList = DomGlobal.document.getElementsByTagName("meta");
    for (int i = 0; i < metaTagList.length; i++) {
      if (metaTagList.item(i) instanceof HTMLMetaElement) {
        HTMLMetaElement nodeListElement = (HTMLMetaElement) metaTagList.item(i);
        if (!Objects.isNull(nodeListElement.name)) {
          if (nodeListElement.name.equals(name)) {
            nodeListElement.remove();
            break;
          }
        }
      }
    }
    HTMLMetaElement metaTagElement = (HTMLMetaElement) DomGlobal.document.createElement("meta");
    metaTagElement.name = name;
    metaTagElement.content = content;
    DomGlobal.document.head.appendChild(metaTagElement);
  }

  @Override
  public void updateMetaPropertyContent(String property,
                                        String content) {
    NodeList<Element> metaTagList = DomGlobal.document.getElementsByTagName("meta");
    for (int i = 0; i < metaTagList.length; i++) {
      if (metaTagList.item(i) instanceof HTMLMetaElement) {
        HTMLMetaElement nodeListElement = (HTMLMetaElement) metaTagList.item(i);
        if (!Objects.isNull(nodeListElement.getAttribute("property"))) {
          if (nodeListElement.getAttribute("property")
                             .equals(property)) {
            nodeListElement.remove();
            break;
          }
        }
      }
    }
    HTMLMetaElement metaElement = (HTMLMetaElement) DomGlobal.document.createElement("meta");
    metaElement.setAttribute("property",
                             property);
    metaElement.content = content;
    DomGlobal.document.head.appendChild(metaElement);
  }

  @Override
  public String decode(String route) {
    return Global.decodeURI(route);
  }

}