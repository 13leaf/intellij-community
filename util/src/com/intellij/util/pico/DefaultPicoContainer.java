/*
 * Copyright 2000-2007 JetBrains s.r.o.
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
package com.intellij.util.pico;

import com.intellij.util.ReflectionCache;
import com.intellij.util.containers.OrderedSet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.picocontainer.*;
import org.picocontainer.defaults.*;

import java.io.Serializable;
import java.util.*;

public class DefaultPicoContainer implements MutablePicoContainer, Serializable {
  private ComponentAdapterFactory componentAdapterFactory;

  private PicoContainer parent;
  private Set<PicoContainer> children = new HashSet<PicoContainer>();

  private Map<Object, ComponentAdapter> componentKeyToAdapterCache = Collections.synchronizedMap(new HashMap<Object, ComponentAdapter>());
  private Collection<ComponentAdapter> componentAdapters = Collections.synchronizedCollection(new OrderedSet<ComponentAdapter>());
  // Keeps track of instantiation order.
  private List<ComponentAdapter> orderedComponentAdapters = Collections.synchronizedList(new OrderedSet<ComponentAdapter>());

  public DefaultPicoContainer(@NotNull ComponentAdapterFactory componentAdapterFactory, PicoContainer parent) {
    this.componentAdapterFactory = componentAdapterFactory;
    this.parent = parent == null ? null : ImmutablePicoContainerProxyFactory.newProxyInstance(parent);
  }


  protected DefaultPicoContainer() {
    this(new DefaultComponentAdapterFactory(), null);
  }

  public Collection<ComponentAdapter> getComponentAdapters() {
    return Collections.unmodifiableCollection(componentAdapters);
  }

  @Nullable
  public final ComponentAdapter getComponentAdapter(Object componentKey) {
    ComponentAdapter adapter = getFromCache(componentKey);
    if (adapter == null && parent != null) {
      adapter = parent.getComponentAdapter(componentKey);
    }
    return adapter;
  }

  @Nullable
  private ComponentAdapter getFromCache(final Object componentKey) {
    ComponentAdapter adapter = componentKeyToAdapterCache.get(componentKey);
    if (adapter != null) return adapter;

    if (componentKey instanceof Class) {
      Class klass = (Class)componentKey;
      return componentKeyToAdapterCache.get(klass.getName());
    }

    return null;
  }

  @Nullable
  public ComponentAdapter getComponentAdapterOfType(Class componentType) {
    // See http://jira.codehaus.org/secure/ViewIssue.jspa?key=PICO-115
    ComponentAdapter adapterByKey = getComponentAdapter(componentType);
    if (adapterByKey != null) {
      return adapterByKey;
    }

    List found = getComponentAdaptersOfType(componentType);

    if (found.size() == 1) {
      return ((ComponentAdapter)found.get(0));
    }
    else if (found.size() == 0) {
      if (parent != null) {
        return parent.getComponentAdapterOfType(componentType);
      }
      else {
        return null;
      }
    }
    else {
      Class[] foundClasses = new Class[found.size()];
      for (int i = 0; i < foundClasses.length; i++) {
        foundClasses[i] = ((ComponentAdapter)found.get(i)).getComponentImplementation();
      }

      throw new AmbiguousComponentResolutionException(componentType, foundClasses);
    }
  }

  public List getComponentAdaptersOfType(Class componentType) {
    if (componentType == null) {
      return Collections.EMPTY_LIST;
    }
    List<ComponentAdapter> found = new ArrayList<ComponentAdapter>();
    for (final Object o : getComponentAdapters()) {
      ComponentAdapter componentAdapter = (ComponentAdapter)o;

      if (ReflectionCache.isAssignable(componentType, componentAdapter.getComponentImplementation())) {
        found.add(componentAdapter);
      }
    }
    return found;
  }

  public ComponentAdapter registerComponent(ComponentAdapter componentAdapter) {
    Object componentKey = componentAdapter.getComponentKey();
    if (componentKeyToAdapterCache.containsKey(componentKey)) {
      throw new DuplicateComponentKeyRegistrationException(componentKey);
    }
    componentAdapters.add(componentAdapter);
    componentKeyToAdapterCache.put(componentKey, componentAdapter);
    return componentAdapter;
  }

  public ComponentAdapter unregisterComponent(Object componentKey) {
    ComponentAdapter adapter = componentKeyToAdapterCache.remove(componentKey);
    componentAdapters.remove(adapter);
    orderedComponentAdapters.remove(adapter);
    return adapter;
  }

  private void addOrderedComponentAdapter(ComponentAdapter componentAdapter) {
    if (!orderedComponentAdapters.contains(componentAdapter)) {
      orderedComponentAdapters.add(componentAdapter);
    }
  }

  public List getComponentInstances() throws PicoException {
    return getComponentInstancesOfType(Object.class);
  }

  public List getComponentInstancesOfType(Class componentType) {
    if (componentType == null) {
      return Collections.EMPTY_LIST;
    }

    Map<ComponentAdapter, Object> adapterToInstanceMap = new HashMap<ComponentAdapter, Object>();
    for (final ComponentAdapter componentAdapter : componentAdapters) {
      if (ReflectionCache.isAssignable(componentType, componentAdapter.getComponentImplementation())) {
        Object componentInstance = getInstance(componentAdapter);
        adapterToInstanceMap.put(componentAdapter, componentInstance);

        // This is to ensure all are added. (Indirect dependencies will be added
        // from InstantiatingComponentAdapter).
        addOrderedComponentAdapter(componentAdapter);
      }
    }

    List result = new ArrayList();
    for (ComponentAdapter componentAdapter : orderedComponentAdapters) {
      final Object componentInstance = adapterToInstanceMap.get(componentAdapter);
      if (componentInstance != null) {
        // may be null in the case of the "implicit" adapter
        // representing "this".
        result.add(componentInstance);
      }
    }
    return result;
  }

  @Nullable
  public Object getComponentInstance(Object componentKey) {
    ComponentAdapter componentAdapter = getComponentAdapter(componentKey);
    if (componentAdapter != null) {
      return getInstance(componentAdapter);
    }
    else {
      return null;
    }
  }

  @Nullable
  public Object getComponentInstanceOfType(Class componentType) {
    final ComponentAdapter componentAdapter = getComponentAdapterOfType(componentType);
    return componentAdapter == null ? null : getInstance(componentAdapter);
  }

  @Nullable
  private Object getInstance(ComponentAdapter componentAdapter) {
    final boolean isLocal = componentAdapters.contains(componentAdapter);

    if (isLocal) {
      return getLocalInstance(componentAdapter);
    }
    else if (parent != null) {
      return parent.getComponentInstance(componentAdapter.getComponentKey());
    }

    return null;
  }

  private Object getLocalInstance(final ComponentAdapter componentAdapter) {
    PicoException firstLevelException = null;
    Object instance = null;
    try {
      instance = componentAdapter.getComponentInstance(this);
    }
    catch (PicoInitializationException e) {
      firstLevelException = e;
    }
    catch (PicoIntrospectionException e) {
      firstLevelException = e;
    }
    if (firstLevelException != null) {
      if (parent != null) {
        instance = parent.getComponentInstance(componentAdapter.getComponentKey());
        if (instance != null) {
          return instance;
        }
      }

      throw firstLevelException;
    }
    addOrderedComponentAdapter(componentAdapter);

    return instance;
  }


  @Nullable
  public ComponentAdapter unregisterComponentByInstance(Object componentInstance) {
    Collection<ComponentAdapter> adapters = getComponentAdapters();

    for (final ComponentAdapter adapter : adapters) {
      final Object o = getInstance(adapter);
      if (o != null && o.equals(componentInstance)) {
        return unregisterComponent(adapter.getComponentKey());
      }
    }
    return null;
  }

  public void verify() throws PicoVerificationException {
    new VerifyingVisitor().traverse(this);
  }

  public void start() {
    throw new UnsupportedOperationException();
  }

  public void stop() {
    throw new UnsupportedOperationException();
  }

  public void dispose() {
    throw new UnsupportedOperationException();
  }

  public MutablePicoContainer makeChildContainer() {
    DefaultPicoContainer pc = new DefaultPicoContainer(componentAdapterFactory, this);
    addChildContainer(pc);
    return pc;
  }

  public boolean addChildContainer(PicoContainer child) {
    return children.add(child);
  }

  public boolean removeChildContainer(PicoContainer child) {
    return children.remove(child);
  }

  public void accept(PicoVisitor visitor) {
    visitor.visitContainer(this);
    final List<ComponentAdapter> adapters = new ArrayList<ComponentAdapter>(getComponentAdapters());
    for (final ComponentAdapter adapter : adapters) {
      adapter.accept(visitor);
    }
    final List<PicoContainer> allChildren = new ArrayList<PicoContainer>(children);
    for (PicoContainer child : allChildren) {
      child.accept(visitor);
    }
  }

  public ComponentAdapter registerComponentInstance(Object component) {
    return registerComponentInstance(component.getClass(), component);
  }

  public ComponentAdapter registerComponentInstance(Object componentKey, Object componentInstance) {
    return registerComponent(new InstanceComponentAdapter(componentKey, componentInstance));
  }

  public ComponentAdapter registerComponentImplementation(Class componentImplementation) {
    return registerComponentImplementation(componentImplementation, componentImplementation);
  }

  public ComponentAdapter registerComponentImplementation(Object componentKey, Class componentImplementation) {
    return registerComponentImplementation(componentKey, componentImplementation, null);
  }

  public ComponentAdapter registerComponentImplementation(Object componentKey, Class componentImplementation, Parameter[] parameters) {
    ComponentAdapter componentAdapter = componentAdapterFactory.createComponentAdapter(componentKey, componentImplementation, parameters);
    return registerComponent(componentAdapter);
  }

  public PicoContainer getParent() {
    return parent;
  }
}